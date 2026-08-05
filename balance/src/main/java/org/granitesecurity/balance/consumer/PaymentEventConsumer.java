package org.granitesecurity.balance.consumer;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.service.BalanceService;
import org.granitesecurity.balance.service.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Credits a balance when a top-up is confirmed — the only way money enters the
 * system from outside (docs/finance/finance.md §6.1).
 *
 * <p><b>The credit happens here and nowhere else.</b> Never on a client claim,
 * never on a redirect return: only on a {@code PaymentSucceeded} that payment
 * published after the provider confirmed the money arrived (D9). Anything able to
 * publish such an event with {@code purpose=TOPUP} can mint credit, which is why
 * kafka-ui has no HTTPRoute in any overlay.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BalanceService balanceService;
    private final IdempotencyService idempotencyService;

    public PaymentEventConsumer(BalanceService balanceService, IdempotencyService idempotencyService) {
        this.balanceService = balanceService;
        this.idempotencyService = idempotencyService;
    }

    @KafkaListener(topics = "payments.events", groupId = "balance.payments.events.consumer")
    public void consume(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = MAPPER.readValue(message, Map.class);

            // Everything on this topic that is not a confirmed top-up belongs to
            // somebody else — order payments are shop's business.
            if (!"TOPUP".equals(string(data.get("purpose")))
                    || !"SUCCEEDED".equals(string(data.get("status")))) {
                return;
            }

            String username = string(data.get("username"));
            String paymentId = string(data.get("paymentId"));
            String amountRaw = string(data.get("amount"));
            if (username == null || paymentId == null || amountRaw == null) {
                log.error("Top-up event missing username/paymentId/amount, cannot credit: {}", message);
                return;
            }

            long amountMinor = new BigDecimal(amountRaw).movePointRight(2).longValueExact();

            // Keyed on the payment: a redelivered event credits once. Kafka is
            // at-least-once, and this is money.
            Mono<CreditRecord> credit = balanceService.move(
                            Account.HOUSE_TOPUP, username, amountMinor,
                            LedgerEntry.KIND_TOPUP, paymentId, "Top-up")
                    .map(transferId -> new CreditRecord(transferId.toString(), username, amountMinor));

            idempotencyService.once("topup:" + paymentId, CreditRecord.class, credit)
                    .subscribe(
                            r -> log.info("Credited {} rappen to {} for top-up {} ({})",
                                    r.amountMinor(), r.username(), paymentId, r.transferId()),
                            e -> log.error("Failed to credit top-up {} for {}", paymentId, username, e));
        } catch (Exception e) {
            log.error("Failed to process payment event: {}", message, e);
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    /** Stored by IdempotencyService so a redelivery replays rather than re-credits. */
    public record CreditRecord(String transferId, String username, long amountMinor) {
    }
}
