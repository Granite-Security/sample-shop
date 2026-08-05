package org.granitesecurity.balance.service;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.repository.AccountRepository;
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

    public BalanceService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          CreditPolicy creditPolicy) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.creditPolicy = creditPolicy;
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

        return findOrCreate(fromUsername).zipWith(findOrCreate(toUsername))
                .flatMap(pair -> {
                    Account from = pair.getT1();
                    Account to = pair.getT2();

                    // Touch the two rows in ascending id order, always. Two users
                    // paying each other at the same moment would otherwise each hold
                    // the row the other wants, and Postgres would kill one of them
                    // as a deadlock. Ordering makes that impossible rather than rare.
                    Mono<Void> debit = debit(from, amountMinor);
                    Mono<Void> credit = accountRepository.credit(to.getId(), amountMinor).then();
                    Mono<Void> updates = from.getId() < to.getId()
                            ? debit.then(credit)
                            : credit.then(debit);

                    return updates
                            .then(writeEntry(transferId, from.getId(), -amountMinor, kind, reference, memo))
                            .then(writeEntry(transferId, to.getId(), amountMinor, kind, reference, memo))
                            .doOnSuccess(v -> log.info("{} {} rappen: {} -> {} ({})",
                                    kind, amountMinor, fromUsername, toUsername, transferId))
                            .thenReturn(transferId);
                });
    }

    /**
     * House accounts are the counterparty to issuance and may go negative without
     * limit — that negative IS the money supply. User accounts are subject to the
     * credit policy.
     */
    private Mono<Void> debit(Account from, long amountMinor) {
        Mono<Long> updated = from.isHouse()
                ? accountRepository.debitUnchecked(from.getId(), amountMinor)
                : accountRepository.debitIf(from.getId(), amountMinor,
                        creditPolicy.minimumBalanceBefore(amountMinor));

        return updated.flatMap(rows -> rows == 0
                ? Mono.error(new InsufficientFundsException(from.getUsername()))
                : Mono.empty());
    }

    private Mono<Void> writeEntry(UUID transferId, Long accountId, long amountMinor,
                                  String kind, String reference, String memo) {
        LedgerEntry entry = new LedgerEntry();
        entry.setTransferId(transferId);
        entry.setAccountId(accountId);
        entry.setAmountMinor(amountMinor);
        entry.setKind(kind);
        entry.setReference(reference);
        entry.setMemo(memo);
        entry.setCreatedAt(Instant.now());
        return ledgerEntryRepository.save(entry).then();
    }

    /** Declined, not errored: the caller turns this into a failed payment, not a 500. */
    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String username) {
            super("Declined: insufficient balance for " + username);
        }
    }
}
