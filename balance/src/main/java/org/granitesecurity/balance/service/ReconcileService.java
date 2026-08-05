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
        return ledgerEntryRepository.sumAll()
                .zipWith(accountRepository.findAll().collectList())
                .flatMap(t -> {
                    long ledgerSum = t.getT1();
                    List<Account> accounts = t.getT2();

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

                        return new ReconcileReport(
                                ledgerSum == 0 && drift.isEmpty() && userTotal + houseTotal == 0,
                                ledgerSum,
                                userTotal,
                                houseTotal,
                                // Reported positive: "how much has been conjured" reads
                                // better than a negative house balance.
                                -houseBalance(accounts, Account.HOUSE_GIFT),
                                -houseBalance(accounts, Account.HOUSE_TOPUP),
                                -houseBalance(accounts, Account.HOUSE_SHOP),
                                -creditOutstanding,
                                drift);
                    });
                });
    }

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
