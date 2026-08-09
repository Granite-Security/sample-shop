package org.granitesecurity.balance.service;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.repository.AccountRepository;
import org.granitesecurity.balance.repository.AccountRepository.Drawdown;
import org.granitesecurity.balance.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Every movement of money in the platform goes through {@link #move}. There is no
 * other write path, and no "set balance" operation exists for anyone (D12).
 *
 * <p><b>No {@code .block()} in this class, ever.</b> The two ledger rows and both
 * cached balances must commit together or not at all, which means a real reactive
 * transaction — blocking inside it would break that guarantee silently.
 */
@Service
public class BalanceService {

    private static final Logger log = LoggerFactory.getLogger(BalanceService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CreditPolicy creditPolicy;
    private final BalanceEventPublisher eventPublisher;

    public BalanceService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          CreditPolicy creditPolicy,
                          BalanceEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.creditPolicy = creditPolicy;
        this.eventPublisher = eventPublisher;
    }

    public Mono<Account> findOrCreate(String username) {
        return accountRepository.findByUsername(username)
                .switchIfEmpty(Mono.defer(() -> create(username)));
    }

    private Mono<Account> create(String username) {
        Account account = new Account();
        account.setUsername(username);
        account.setKind(Account.KIND_USER);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return accountRepository.save(account)
                // Two requests can race to create the same account; the UNIQUE
                // constraint decides, and the loser re-reads rather than failing.
                .onErrorResume(e -> accountRepository.findByUsername(username)
                        .switchIfEmpty(Mono.error(e)));
    }

    public Mono<Account> requireAccount(String username) {
        return accountRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No account for " + username)));
    }

    public Flux<LedgerEntry> transactions(String username, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        long offset = (long) Math.max(page, 0) * limit;
        return findOrCreate(username)
                .flatMapMany(account -> ledgerEntryRepository.findByAccount(account.getId(), limit, offset));
    }

    /**
     * Moves {@code amountMinor} rappen from one account to another, writing both
     * ledger legs and both cached balances in one transaction.
     *
     * <p>The debit side carries {@link CreditPolicy} as a WHERE predicate when the
     * payer is a user account. Zero rows updated means declined — the money did not
     * move and nothing was written.
     *
     * @return the transfer id shared by the two entries
     */
    @Transactional
    public Mono<UUID> move(String fromUsername, String toUsername, long amountMinor,
                           String kind, String reference, String memo) {
        if (amountMinor <= 0) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Amount must be positive"));
        }
        if (fromUsername.equals(toUsername)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot move money to the same account"));
        }

        UUID transferId = UUID.randomUUID();
        // One timestamp for the whole movement: both ledger legs and the outbox row
        // that announces them. The books post by the event's own business date (§6),
        // and a ledger that disagrees with its own announcement about when money moved
        // is not something a later reconciliation can resolve.
        Instant occurredAt = Instant.now();

        return findOrCreate(fromUsername).zipWith(findOrCreate(toUsername))
                .flatMap(pair -> {
                    Account from = pair.getT1();
                    Account to = pair.getT2();

                    // Touch the two rows in ascending id order, always. Two users
                    // paying each other at the same moment would otherwise each hold
                    // the row the other wants, and Postgres would kill one of them
                    // as a deadlock. Ordering makes that impossible rather than rare.
                    Mono<Drawdown> debit = debit(from, amountMinor);
                    Mono<Void> credit = accountRepository.credit(to.getId(), amountMinor).then();
                    Mono<Drawdown> updates = from.getId() < to.getId()
                            ? debit.flatMap(drawn -> credit.thenReturn(drawn))
                            : credit.then(debit);

                    return updates.flatMap(drawn -> giftArriving(kind, to, amountMinor, reference, drawn)
                            .flatMap(giftIn -> propagateGift(to, giftIn)
                                    .then(writeEntry(transferId, from.getId(), -amountMinor, kind,
                                            reference, memo, drawn.giftDrawn(), drawn.creditDrawn(),
                                            occurredAt))
                                    .then(writeEntry(transferId, to.getId(), amountMinor, kind,
                                            reference, memo, giftIn, 0L, occurredAt))
                                    // Same transaction as the entries above, which is the
                                    // entire point of an outbox: a movement and its
                                    // announcement cannot diverge.
                                    .then(eventPublisher.publish(kind, from, to, amountMinor, drawn,
                                            giftIn, transferId, reference, memo, occurredAt))
                                    .doOnSuccess(v -> log.info("{} {} rappen: {} -> {} ({}) gift={} credit={}",
                                            kind, amountMinor, fromUsername, toUsername, transferId,
                                            drawn.giftDrawn(), drawn.creditDrawn()))
                                    .thenReturn(transferId)));
                });
    }

    /**
     * House accounts are the counterparty to issuance and may go negative without
     * limit — that negative IS the money supply. User accounts are subject to the
     * credit policy, and their debits carry the gift/backed/credit split.
     *
     * <p>A house debit has no split to make: house:gift is where conjured money comes
     * from, so asking which of its francs were gifted is not a question. It reports a
     * zero drawdown, and balance/003's CHECK constraint keeps that true in the schema
     * as well as here.
     */
    private Mono<Drawdown> debit(Account from, long amountMinor) {
        if (from.isHouse()) {
            return accountRepository.debitUnchecked(from.getId(), amountMinor)
                    .flatMap(rows -> rows == 0
                            ? Mono.error(new InsufficientFundsException(from.getUsername()))
                            : Mono.just(Drawdown.NONE));
        }
        // An empty result is the decline: the conditional UPDATE matched no row, so
        // nothing moved and nothing was written.
        return accountRepository.debitIf(from.getId(), amountMinor,
                        creditPolicy.minimumBalanceBefore(amountMinor))
                .switchIfEmpty(Mono.error(new InsufficientFundsException(from.getUsername())));
    }

    /**
     * How much conjured money arrives on the credit side (docs/finance/accounting.md §5.2).
     *
     * <p>The TRANSFER row is the load-bearing one. If a transfer moved money without
     * carrying its funding, one user-to-user hop would launder every gifted franc into
     * apparently-backed money, and both the money-supply report and the contra-revenue
     * line would quietly read zero.
     *
     * <p>A REFUND puts gifted money back into the pool it was drawn from rather than
     * crediting it as backed money — otherwise refunding a gift-funded order would
     * convert conjured money into real money, which is the one thing the two-door rule
     * exists to prevent.
     */
    private Mono<Long> giftArriving(String kind, Account to, long amountMinor,
                                    String reference, Drawdown drawn) {
        if (to.isHouse()) {
            return Mono.just(0L);
        }
        return switch (kind) {
            case LedgerEntry.KIND_GIFT -> Mono.just(amountMinor);
            case LedgerEntry.KIND_TRANSFER -> Mono.just(drawn.giftDrawn());
            case LedgerEntry.KIND_REFUND -> reference == null
                    ? Mono.just(0L)
                    : ledgerEntryRepository.giftOutstandingOn(to.getId(), reference)
                            .map(outstanding -> Math.max(0, Math.min(amountMinor, outstanding)));
            // A top-up is backed money by definition, and a spend credits a house account.
            default -> Mono.just(0L);
        };
    }

    private Mono<Void> propagateGift(Account to, long giftMinor) {
        return giftMinor <= 0
                ? Mono.empty()
                : accountRepository.addGiftPool(to.getId(), giftMinor).then();
    }

    private Mono<Void> writeEntry(UUID transferId, Long accountId, long amountMinor,
                                  String kind, String reference, String memo,
                                  long giftFundedMinor, long creditFundedMinor,
                                  Instant occurredAt) {
        LedgerEntry entry = new LedgerEntry();
        entry.setTransferId(transferId);
        entry.setAccountId(accountId);
        entry.setAmountMinor(amountMinor);
        entry.setKind(kind);
        entry.setReference(reference);
        entry.setMemo(memo);
        entry.setGiftFundedMinor(giftFundedMinor);
        entry.setCreditFundedMinor(creditFundedMinor);
        entry.setCreatedAt(occurredAt);
        return ledgerEntryRepository.save(entry).then();
    }

    /** Declined, not errored: the caller turns this into a failed payment, not a 500. */
    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String username) {
            super("Declined: insufficient balance for " + username);
        }
    }
}
