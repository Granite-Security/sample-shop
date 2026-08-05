package org.granitesecurity.balance.service;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.BalanceIntent;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.repository.AccountRepository;
import org.granitesecurity.balance.repository.BalanceIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * The provider-facing half of balance: create → capture → refund, mirroring
 * PayPal's order model so `payment` can treat balance as an ordinary provider
 * (docs/finance/finance.md §4.1).
 *
 * <p><b>Creating an intent moves no money.</b> It records the request and gives
 * fast feedback on whether the credit policy would allow it; the authoritative
 * decision is taken inside capture's conditional UPDATE, because funds can move
 * between the two calls.
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private final BalanceIntentRepository intentRepository;
    private final AccountRepository accountRepository;
    private final BalanceService balanceService;
    private final CreditPolicy creditPolicy;

    public IntentService(BalanceIntentRepository intentRepository,
                         AccountRepository accountRepository,
                         BalanceService balanceService,
                         CreditPolicy creditPolicy) {
        this.intentRepository = intentRepository;
        this.accountRepository = accountRepository;
        this.balanceService = balanceService;
        this.creditPolicy = creditPolicy;
    }

    public Mono<BalanceIntent> create(String username, long amountMinor, Long orderId) {
        if (amountMinor <= 0) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive"));
        }
        return balanceService.findOrCreate(username)
                .flatMap(account -> {
                    BalanceIntent intent = new BalanceIntent();
                    intent.setId(UUID.randomUUID());
                    intent.setUsername(username);
                    intent.setAmountMinor(amountMinor);
                    intent.setOrderId(orderId);
                    intent.setCreatedAt(Instant.now());
                    intent.setUpdatedAt(Instant.now());
                    intent.markNew();

                    // Advisory only. A CREATED intent that would be declined right now
                    // is still recorded, because the shopper may top up before
                    // capture — but saying so early saves a pointless redirect.
                    boolean wouldDecline = account.getBalanceMinor()
                            < creditPolicy.minimumBalanceBefore(amountMinor);
                    if (wouldDecline) {
                        intent.setStatus(BalanceIntent.FAILED);
                        intent.setDeclineReason("Insufficient balance");
                    } else {
                        intent.setStatus(BalanceIntent.CREATED);
                    }
                    return intentRepository.save(intent);
                })
                .doOnSuccess(i -> log.info("Intent {} {} for {} ({} rappen, order {})",
                        i.getId(), i.getStatus(), username, amountMinor, orderId));
    }

    public Mono<BalanceIntent> get(UUID id) {
        return intentRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such intent: " + id)));
    }

    /**
     * Takes the money. <b>Must be idempotent</b> — the return endpoint and /sync race
     * on this routinely, and an already-captured intent has to resolve successfully
     * by reporting current state rather than charging twice
     * ({@code RedirectPaymentProvider#finalizePayment}).
     */
    public Mono<BalanceIntent> capture(UUID id) {
        return get(id).flatMap(intent -> {
            if (BalanceIntent.CAPTURED.equals(intent.getStatus())
                    || BalanceIntent.REFUNDED.equals(intent.getStatus())) {
                log.info("Intent {} already {}, nothing to capture", id, intent.getStatus());
                return Mono.just(intent);
            }
            return balanceService.move(
                            intent.getUsername(),
                            Account.HOUSE_SHOP,
                            intent.getAmountMinor(),
                            LedgerEntry.KIND_SPEND,
                            intent.getOrderId() == null ? null : String.valueOf(intent.getOrderId()),
                            "Order " + intent.getOrderId())
                    .flatMap(transferId -> {
                        intent.setStatus(BalanceIntent.CAPTURED);
                        intent.setTransferId(transferId);
                        intent.setDeclineReason(null);
                        intent.setUpdatedAt(Instant.now());
                        return intentRepository.save(intent);
                    })
                    // A decline is an outcome, not a failure: the intent records it and
                    // payment turns it into PAYMENT_FAILED through the path that
                    // already exists for a declined card.
                    .onErrorResume(BalanceService.InsufficientFundsException.class, e -> {
                        intent.setStatus(BalanceIntent.FAILED);
                        intent.setDeclineReason("Insufficient balance");
                        intent.setUpdatedAt(Instant.now());
                        return intentRepository.save(intent);
                    });
        });
    }

    /** Compensating credit, from house:refund back to the user (D7). */
    public Mono<BalanceIntent> refund(UUID id) {
        return get(id).flatMap(intent -> {
            if (BalanceIntent.REFUNDED.equals(intent.getStatus())) {
                return Mono.just(intent);
            }
            if (!BalanceIntent.CAPTURED.equals(intent.getStatus())) {
                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                        "Intent " + id + " is " + intent.getStatus() + ", nothing to refund"));
            }
            return balanceService.move(
                            Account.HOUSE_REFUND,
                            intent.getUsername(),
                            intent.getAmountMinor(),
                            LedgerEntry.KIND_REFUND,
                            intent.getOrderId() == null ? null : String.valueOf(intent.getOrderId()),
                            "Refund for order " + intent.getOrderId())
                    .flatMap(refundId -> {
                        intent.setStatus(BalanceIntent.REFUNDED);
                        intent.setRefundId(refundId);
                        intent.setUpdatedAt(Instant.now());
                        return intentRepository.save(intent);
                    });
        });
    }
}
