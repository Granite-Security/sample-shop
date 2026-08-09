package org.granitesecurity.balance.service;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.dto.AccountDrift;
import org.granitesecurity.balance.dto.ReconcileReport;
import org.granitesecurity.balance.repository.AccountRepository;
import org.granitesecurity.balance.repository.LedgerEntryRepository;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Proves the ledger rather than trusting it (docs/finance/finance.md §7.1).
 *
 * <p>A cached balance is an optimisation, and optimisations drift. Run this after
 * every deploy: a non-zero {@code ledgerSum} means money was created or destroyed
 * outside the two doors, which is a bug that has already moved money.
 */
@Service
public class ReconcileService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public ReconcileService(AccountRepository accountRepository,
                            LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public Mono<ReconcileReport> reconcile() {
        return Mono.zip(ledgerEntryRepository.sumAll(),
                        accountRepository.findAll().collectList(),
                        funding())
                .flatMap(t -> {
                    long ledgerSum = t.getT1();
                    List<Account> accounts = t.getT2();
                    Funding funding = t.getT3();

                    return checkDrift(accounts).map(drift -> {
                        long userTotal = accounts.stream()
                                .filter(a -> !a.isHouse())
                                .mapToLong(Account::getBalanceMinor).sum();
                        long houseTotal = accounts.stream()
                                .filter(Account::isHouse)
                                .mapToLong(Account::getBalanceMinor).sum();
                        long creditOutstanding = accounts.stream()
                                .filter(a -> !a.isHouse())
                                .mapToLong(Account::getBalanceMinor)
                                .filter(b -> b < 0)
                                .sum();

                        long giftedOutstanding = accounts.stream()
                                .mapToLong(Account::getGiftPoolMinor).sum();
                        // NOT negated, unlike the two issuance figures below. A spend moves
                        // user -> house:shop, so house:shop is *credited* and its balance is
                        // already positive; negating it reported every franc ever spent as a
                        // negative number.
                        long redeemed = houseBalance(accounts, Account.HOUSE_SHOP);

                        // Invariant (§12.1): every conjured franc is either still in
                        // someone's pool or has been drawn out of one. Non-zero means the
                        // funding split is lying, and since the split feeds contra-revenue,
                        // so is revenue.
                        long giftPoolDrift = giftedOutstanding - funding.netGiftIntoPools();

                        // gift + backed + credit = spend (D13). Backed is the remainder on
                        // purpose: a stored third column is a third thing that can disagree
                        // with the other two.
                        long spentFromBacked = redeemed - funding.spentFromGift() - funding.spentFromCredit();

                        return new ReconcileReport(
                                ledgerSum == 0 && drift.isEmpty() && userTotal + houseTotal == 0
                                        && giftPoolDrift == 0 && funding.violations() == 0,
                                ledgerSum,
                                userTotal,
                                houseTotal,
                                // Reported positive: "how much has been conjured" reads
                                // better than a negative house balance. house:gift and
                                // house:topup are debited when they issue, so these two are
                                // the ones that need the sign flipped.
                                -houseBalance(accounts, Account.HOUSE_GIFT),
                                -houseBalance(accounts, Account.HOUSE_TOPUP),
                                redeemed,
                                -creditOutstanding,
                                giftedOutstanding,
                                funding.spentFromGift(),
                                spentFromBacked,
                                funding.spentFromCredit(),
                                giftPoolDrift,
                                funding.violations(),
                                drift);
                    });
                });
    }

    private Mono<Funding> funding() {
        return Mono.zip(ledgerEntryRepository.netGiftIntoPools(),
                        ledgerEntryRepository.spentFromGift(),
                        ledgerEntryRepository.spentFromCredit(),
                        ledgerEntryRepository.countFundingViolations())
                .map(t -> new Funding(t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }

    private record Funding(long netGiftIntoPools, long spentFromGift,
                           long spentFromCredit, long violations) {}

    /** Invariant 2, per account: the cache must equal the sum of its entries. */
    private Mono<List<AccountDrift>> checkDrift(List<Account> accounts) {
        return reactor.core.publisher.Flux.fromIterable(accounts)
                .flatMap(account -> ledgerEntryRepository.sumForAccount(account.getId())
                        .map(sum -> new AccountDrift(account.getUsername(),
                                account.getBalanceMinor(), sum)))
                .filter(AccountDrift::drifted)
                .collectList()
                .map(ArrayList::new);
    }

    private static long houseBalance(List<Account> accounts, String username) {
        return accounts.stream()
                .filter(a -> username.equals(a.getUsername()))
                .mapToLong(Account::getBalanceMinor)
                .findFirst()
                .orElse(0L);
    }
}
