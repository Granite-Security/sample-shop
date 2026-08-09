package org.granitesecurity.balance.service;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.domain.OutboxEvent;
import org.granitesecurity.balance.repository.AccountRepository.Drawdown;
import org.granitesecurity.balance.repository.OutboxRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a movement into the fact the books need (docs/finance/accounting.md §4.2).
 *
 * <p>Called from inside {@code BalanceService.move}'s transaction, so the outbox row
 * and the ledger rows commit together or not at all.
 *
 * <p><b>Facts, never journal instructions (D24).</b> These payloads say "alice spent
 * 6000 rappen, 5000 of it gifted"; they never say "Dr 2000 / Cr 2010". Which accounts
 * that touches is accounting policy, it lives in one file in one service, and it has
 * to be changeable without a release of this one.
 *
 * <p>Every money-relevant field is frozen here, at emit time (D26). A consumer that
 * had to ask balance what the split was would make the books depend on a live service
 * and stop being reproducible.
 */
@Component
public class BalanceEventPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Payload version. Once a payload has been booked it is a contract (§16.1). */
    private static final int SCHEMA_VERSION = 1;

    public static final String GIFT_ISSUED = "GiftIssued";
    public static final String SPENT = "Spent";
    public static final String REFUNDED = "Refunded";
    public static final String TRANSFERRED = "Transferred";

    private final OutboxRepository outboxRepository;

    public BalanceEventPublisher(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * @param giftInMinor conjured money arriving on the credit side: the whole of a
     *                    gift, the drawn portion of a transfer, or the part of a refund
     *                    being returned to the pool it came from
     */
    public Mono<Void> publish(String kind, Account from, Account to, long amountMinor,
                              Drawdown drawn, long giftInMinor, UUID transferId,
                              String reference, String memo, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String eventType;
        String aggregateId;

        switch (kind) {
            case LedgerEntry.KIND_GIFT -> {
                eventType = GIFT_ISSUED;
                aggregateId = to.getUsername();
                payload.put("username", to.getUsername());
                payload.put("amountMinor", amountMinor);
                // reference carries the acting admin on a gift; memo carries the reason.
                payload.put("issuedBy", reference);
                payload.put("reason", memo);
            }
            case LedgerEntry.KIND_SPEND -> {
                eventType = SPENT;
                aggregateId = from.getUsername();
                payload.put("username", from.getUsername());
                payload.put("amountMinor", amountMinor);
                payload.put("giftFundedMinor", drawn.giftDrawn());
                payload.put("creditFundedMinor", drawn.creditDrawn());
                payload.put("orderId", reference);
            }
            case LedgerEntry.KIND_REFUND -> {
                eventType = REFUNDED;
                aggregateId = to.getUsername();
                payload.put("username", to.getUsername());
                payload.put("amountMinor", amountMinor);
                // What went back into the gift pool. The rest of the reversal — which
                // legs to unwind and in what proportion — comes from the Spent event
                // carrying the same orderId, which the books already hold.
                payload.put("giftRestoredMinor", giftInMinor);
                payload.put("orderId", reference);
            }
            case LedgerEntry.KIND_TRANSFER -> {
                eventType = TRANSFERRED;
                aggregateId = from.getUsername();
                payload.put("fromUsername", from.getUsername());
                payload.put("toUsername", to.getUsername());
                payload.put("amountMinor", amountMinor);
                payload.put("giftFundedMinor", drawn.giftDrawn());
                payload.put("creditFundedMinor", drawn.creditDrawn());
            }
            // A top-up is already announced by payment as PaymentSucceeded with
            // purpose=TOPUP, and the books consume that. Publishing it here as well
            // would give one arrival of cash two producers and let it be booked twice.
            default -> {
                return Mono.empty();
            }
        }

        payload.put("eventType", eventType);
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("transferId", transferId.toString());
        payload.put("currency", to.getCurrency());
        // The business date the books post by (§6). Shared with the ledger entries of
        // the same movement, so the two can never disagree about when it happened.
        payload.put("occurredAt", occurredAt.toString());

        // backedFundedMinor is deliberately absent: it is amount - gift - credit, and a
        // third number on the wire is a third thing that can contradict the other two.
        OutboxEvent event = new OutboxEvent(aggregateId, eventType, MAPPER.writeValueAsString(payload));
        return outboxRepository.save(event).then();
    }
}
