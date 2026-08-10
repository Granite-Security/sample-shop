package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Fact;
import org.granitesecurity.accounting.repository.FactRepository;
import org.granitesecurity.accounting.service.PostingOutcome.Ignore;
import org.granitesecurity.accounting.service.PostingOutcome.Post;
import org.granitesecurity.accounting.service.PostingOutcome.PostingLine;
import org.granitesecurity.accounting.service.PostingOutcome.Wait;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The §4.4 table, and nothing else. <b>This is the only place that knows what an event
 * means in accounting terms</b> — the reason this is a service and not a query
 * (docs/finance/accounting.md §1.1).
 *
 * <p>Producers publish facts, never journal instructions (D24). shop says "order 42, total
 * 60.00"; the decision that this touches 2010 rather than 4000 is made here, and changing
 * it is a release of this service alone.
 *
 * <p>Prerequisites are read from stored facts, never from the producing service (D25). If
 * accounting asked shop what an order cost, the books would depend on a live service and
 * stop being reproducible.
 *
 * <p><b>Not here yet:</b> cost of goods, shipping and processor fees (§2.8). They need
 * {@code unitCost} frozen onto OrderPlaced, which is step 8 — a fee rule that guesses at a
 * cost basis is worse than no fee rule.
 */
@Component
public class PostingRules {

    static final String TOPIC_ORDERS = "orders.events";
    static final String TOPIC_PAYMENTS = "payments.events";
    static final String TOPIC_DELIVERY = "delivery.events";
    static final String TOPIC_BALANCE = "balance.events";

    /** One entity, one currency, no FX anywhere in this system (D14, §1.2). */
    static final String BOOKS_CURRENCY = "CHF";

    /** payment's own name for the provider that pays from a platform balance. */
    private static final String PROVIDER_BALANCE = "balance";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FactRepository factRepository;
    private final CostPolicy costPolicy;

    public PostingRules(FactRepository factRepository, CostPolicy costPolicy) {
        this.factRepository = factRepository;
        this.costPolicy = costPolicy;
    }

    public Mono<PostingOutcome> derive(Fact fact) {
        Map<String, Object> payload = parse(fact.getPayload());

        String currency = string(payload.get("currency"));
        if (currency != null && !BOOKS_CURRENCY.equalsIgnoreCase(currency)) {
            // shop priced in USD before the 2026-08-01 cutover. Summing across currencies
            // is meaningless and there is no FX, so these are recorded and not booked
            // rather than silently converted at some rate nobody chose.
            return Mono.just(new Ignore("currency " + currency + " is not " + BOOKS_CURRENCY));
        }

        return switch (fact.getTopic()) {
            case TOPIC_ORDERS -> orders(fact, payload);
            case TOPIC_PAYMENTS -> payments(fact, payload);
            case TOPIC_DELIVERY -> delivery(fact, payload);
            case TOPIC_BALANCE -> balance(fact, payload);
            default -> Mono.just(new Ignore("unknown topic " + fact.getTopic()));
        };
    }

    // ---------------------------------------------------------------- orders.events

    private Mono<PostingOutcome> orders(Fact fact, Map<String, Object> payload) {
        return switch (fact.getEventType()) {
            // Contract inception is not a transaction: nothing has been performed and
            // nothing has moved. The fact is stored because delivery needs its total.
            case "OrderPlaced" -> Mono.just(new Ignore("contract inception is not a transaction"));
            case "RefundRequested" -> refundRequested(fact, payload);
            case "StockAdjusted" -> Mono.just(stockAdjusted(payload));
            default -> Mono.just(new Ignore("no rule for " + fact.getEventType()));
        };
    }

    /**
     * A return is requested, not yet settled: recognise the refund liability now and take
     * back what was recognised (§4.4).
     *
     * <p>What is taken back depends on whether the sale was ever recognised. After delivery
     * the reversal is of revenue, and it must reverse <em>both</em> legs — gross revenue and
     * the gift contra — or a refunded gift-funded order would leave contra-revenue standing
     * against a sale that no longer exists. Before delivery nothing was recognised, so what
     * unwinds is the deferred revenue.
     */
    private Mono<PostingOutcome> refundRequested(Fact fact, Map<String, Object> payload) {
        long gross = Money.fromDecimal(payload.get("total"));
        if (gross <= 0) {
            return Mono.just(new Ignore("nothing to reverse"));
        }
        return giftFundedOn(fact.getAggregateId()).flatMap(gift ->
                wasDelivered(fact.getAggregateId()).map(delivered -> {
                    long net = gross - gift;
                    List<PostingLine> lines = new ArrayList<>();
                    if (delivered) {
                        lines.add(PostingLine.debit(Accounts.REVENUE, gross));
                        if (gift > 0) {
                            lines.add(PostingLine.credit(Accounts.CONTRA_GIFT, gift));
                        }
                        if (net > 0) {
                            lines.add(PostingLine.credit(Accounts.REFUND_LIABILITY, net));
                        }
                        return new Post(lines, "Return requested on a delivered order");
                    }
                    if (net <= 0) {
                        // Entirely gift-funded and never delivered: no cash was taken and
                        // no revenue was recognised, so there is nothing to give back.
                        return (PostingOutcome) new Ignore("gift-funded and undelivered: nothing was booked");
                    }
                    lines.add(PostingLine.debit(Accounts.DEFERRED_REVENUE, net));
                    lines.add(PostingLine.credit(Accounts.REFUND_LIABILITY, net));
                    return new Post(lines, "Return requested before delivery");
                }));
    }

    // -------------------------------------------------------------- payments.events

    /**
     * payments.events carries no {@code eventType} field — shop branches on {@code status}
     * and so does this. Keep the two in step: a status this does not recognise is recorded
     * and not booked, never guessed at.
     */
    private Mono<PostingOutcome> payments(Fact fact, Map<String, Object> payload) {
        String status = string(payload.get("status"));
        String provider = string(payload.get("provider"));
        long amount = Money.fromDecimal(payload.get("amount"));

        if (status == null) {
            return Mono.just(new Ignore("payment event with no status"));
        }

        return switch (status) {
            case "SUCCEEDED" -> Mono.just(succeeded(payload, provider, amount));
            // A payment that never happened moves nothing. It is not a loss and not a
            // write-off; there is simply no transaction.
            case "FAILED", "CANCELED" -> Mono.just(new Ignore("payment did not complete"));
            case "REFUNDED" -> Mono.just(refundSettled(provider, amount));
            case "REFUND_FAILED" -> Mono.just(refundReversed(provider, amount));
            default -> Mono.just(new Ignore("no rule for payment status " + status));
        };
    }

    /**
     * The fee is netted against the cash line rather than posted as its own entry, because
     * that is what actually happens: a processor deposits the amount less its fee, and
     * {@code 1000} is the processor balance as much as the bank (D31). The gross liability
     * is still credited in full — the fee is our cost, not a discount to the customer.
     *
     * <p>It is expensed at <b>capture</b>, in the period of the payment, not the period of
     * the delivery (§2.9). Revenue and its processor fee can therefore land in different
     * months, which is correct: the fee buys the payment, not the sale.
     */
    private PostingOutcome succeeded(Map<String, Object> payload, String provider, long amount) {
        if (amount <= 0) {
            return new Ignore("zero-amount payment");
        }
        long fee = costPolicy.processorFee(provider, amount);

        // A top-up is stored value, not revenue: we owe goods or a refund and have earned
        // nothing (§2.3). |house:topup| must never appear on a revenue line. It still
        // incurs a fee, with no sale anywhere to match it against — simply an expense.
        if ("TOPUP".equalsIgnoreCase(string(payload.get("purpose")))) {
            return new Post(withFee(Accounts.STORED_VALUE, amount, fee), "Top-up received");
        }
        if (PROVIDER_BALANCE.equalsIgnoreCase(provider)) {
            // No cash arrives when an order is paid from a platform balance — a liability
            // converts, and only balance knows in what proportion. Its Spent event carries
            // the split and posts all three legs (§4.4). No processor, so no fee either.
            return new Ignore("balance-funded: the Spent event carries the funding split");
        }
        return new Post(withFee(Accounts.DEFERRED_REVENUE, amount, fee),
                "Order paid — goods not yet delivered");
    }

    private List<PostingLine> withFee(String creditAccount, long amount, long fee) {
        List<PostingLine> lines = new ArrayList<>();
        lines.add(PostingLine.debit(Accounts.CASH, amount - fee));
        if (fee > 0) {
            lines.add(PostingLine.debit(Accounts.PROCESSOR_FEES, fee));
        }
        lines.add(PostingLine.credit(creditAccount, amount));
        return lines;
    }

    /**
     * An admin's absolute stock overwrite (§14.1). The reason chooses the contra account,
     * which is the entire reason the event carries one: stock arriving is an asset we owe a
     * supplier for, stock vanishing is an expense. Without a reason accounting could not
     * pick between them even knowing the size, and an unexplained inventory movement is
     * exactly what a general ledger must not contain.
     */
    private PostingOutcome stockAdjusted(Map<String, Object> payload) {
        long quantity = count(payload.get("quantity"));
        long unitCost = Money.fromDecimal(payload.get("unitCost"));
        long value = Math.abs(quantity) * unitCost;
        if (value == 0) {
            return new Ignore("stock movement with no value");
        }
        String reason = string(payload.get("reason"));

        if (quantity > 0) {
            if ("RECEIPT".equalsIgnoreCase(reason)) {
                // Goods arrived and someone will invoice us for them.
                return new Post(List.of(
                        PostingLine.debit(Accounts.INVENTORY, value),
                        PostingLine.credit(Accounts.ACCOUNTS_PAYABLE, value)),
                        "Stock received");
            }
            // A count correction upwards is not a purchase: nothing was bought and nobody
            // is owed. It reverses a previous write-off, so it goes back against 6200.
            return new Post(List.of(
                    PostingLine.debit(Accounts.INVENTORY, value),
                    PostingLine.credit(Accounts.INVENTORY_ADJUSTMENTS, value)),
                    "Stock corrected upwards (" + reason + ")");
        }
        return new Post(List.of(
                PostingLine.debit(Accounts.INVENTORY_ADJUSTMENTS, value),
                PostingLine.credit(Accounts.INVENTORY, value)),
                "Stock written down (" + reason + ")");
    }

    private PostingOutcome refundSettled(String provider, long amount) {
        if (PROVIDER_BALANCE.equalsIgnoreCase(provider)) {
            return new Ignore("balance-funded: the Refunded event carries the funding split");
        }
        return new Post(List.of(
                PostingLine.debit(Accounts.REFUND_LIABILITY, amount),
                PostingLine.credit(Accounts.CASH, amount)),
                "Refund settled");
    }

    /**
     * The provider accepted a refund and then failed it at the bank. Reverse the
     * settlement and <b>keep the liability</b>: the order walks back to RETURNED and the
     * money is still owed (§4.4). Writing the liability off here would make a customer
     * who is still owed money disappear from the books.
     */
    private PostingOutcome refundReversed(String provider, long amount) {
        if (PROVIDER_BALANCE.equalsIgnoreCase(provider)) {
            return new Ignore("balance-funded: nothing left the bank to put back");
        }
        return new Post(List.of(
                PostingLine.debit(Accounts.CASH, amount),
                PostingLine.credit(Accounts.REFUND_LIABILITY, amount)),
                "Refund failed at the bank — liability stands");
    }

    // -------------------------------------------------------------- delivery.events

    /**
     * <b>The recognition point.</b> Control of the goods passes to the customer on
     * delivery, not on payment and not on despatch (IFRS 15.31/.38, §2.1) — which is why
     * DISPATCHED is recorded and books nothing.
     *
     * <p>Revenue is credited gross and the gifted part is debited to contra-revenue, so the
     * discount stays visible instead of being netted away. Deferred revenue only ever
     * received the net amount, because gifted credit never entered the books at all (§4.5).
     */
    private Mono<PostingOutcome> delivery(Fact fact, Map<String, Object> payload) {
        String status = string(payload.get("status"));
        if (!"DELIVERED".equalsIgnoreCase(status)) {
            return Mono.just(new Ignore("control passes on delivery, not on " + status));
        }
        String orderId = fact.getAggregateId();

        return factRepository.findByAggregateAndType(orderId, "OrderPlaced").next()
                .map(this::parseFact)
                .flatMap(orderPayload -> giftFundedOn(orderId).map(gift -> {
                    long gross = Money.fromDecimal(orderPayload.get("total"));
                    if (gross <= 0) {
                        return (PostingOutcome) new Ignore("order has no value to recognise");
                    }
                    long net = gross - gift;
                    List<PostingLine> lines = new ArrayList<>();
                    if (net > 0) {
                        lines.add(PostingLine.debit(Accounts.DEFERRED_REVENUE, net));
                    }
                    if (gift > 0) {
                        lines.add(PostingLine.debit(Accounts.CONTRA_GIFT, gift));
                    }
                    lines.add(PostingLine.credit(Accounts.REVENUE, gross));

                    // The cost side rides the same entry, so the whole cost of a sale sits
                    // in the period of its revenue — which is the point of recognising both
                    // at delivery rather than one at order and one at despatch.
                    long cogs = costOfGoods(orderPayload);
                    if (cogs > 0) {
                        lines.add(PostingLine.debit(Accounts.COGS, cogs));
                        lines.add(PostingLine.credit(Accounts.INVENTORY, cogs));
                    }
                    long shipping = costPolicy.shippingMinor();
                    if (shipping > 0) {
                        // A fulfilment cost, not a performance obligation: we do not charge
                        // for shipping, so there is no shipping revenue to allocate and
                        // nothing to recognise separately (§2.10).
                        lines.add(PostingLine.debit(Accounts.SHIPPING, shipping));
                        lines.add(PostingLine.credit(Accounts.CASH, shipping));
                    }
                    return new Post(lines, "Delivered — revenue recognised, cost of sale matched");
                }))
                // The order itself has not arrived yet. Across four topics that is ordinary,
                // not exceptional: wait for it rather than book a sale of unknown value.
                .switchIfEmpty(Mono.just(new Wait("OrderPlaced for order " + orderId)));
    }

    // --------------------------------------------------------------- balance.events

    private Mono<PostingOutcome> balance(Fact fact, Map<String, Object> payload) {
        return switch (fact.getEventType()) {
            case "Spent" -> Mono.just(spent(payload));
            case "Refunded" -> refunded(fact, payload);
            // No grant-date journal exists at all. The reduction of revenue is recognised
            // at the later of promising the consideration and recognising the revenue,
            // which is always the delivery (IFRS 15.72, §2.4). Outstanding gift credit is
            // a memo figure balance already measures exactly — not a booked liability.
            case "GiftIssued" -> Mono.just(new Ignore("gifted credit is contra-revenue at delivery, not at grant"));
            // Conjured money changing hands between users. It moves no real value and
            // creates no obligation the books recognise; balance tracks whose pool it is in.
            case "Transferred" -> Mono.just(new Ignore("transfers move gift credit, which is not booked"));
            default -> Mono.just(new Ignore("no rule for " + fact.getEventType()));
        };
    }

    /**
     * The three balance-funded legs (§4.4). This is why the funding split has to exist:
     * one debit of CHF 60 can be three different postings, and only balance can decide
     * which atomically.
     */
    private PostingOutcome spent(Map<String, Object> payload) {
        long amount = Money.fromMinor(payload.get("amountMinor"));
        long gift = Money.fromMinor(payload.get("giftFundedMinor"));
        long credit = Money.fromMinor(payload.get("creditFundedMinor"));
        long backed = amount - gift - credit;

        List<PostingLine> lines = new ArrayList<>();
        if (backed > 0) {
            // No new cash: a liability converts from stored value to deferred revenue.
            lines.add(PostingLine.debit(Accounts.STORED_VALUE, backed));
        }
        if (credit > 0) {
            lines.add(PostingLine.debit(Accounts.RECEIVABLES, credit));
        }
        if (backed + credit <= 0) {
            // Entirely gifted. Under policy (b) it is a discount, not consideration, so it
            // never enters deferred revenue and there is nothing to book here at all.
            return new Ignore("entirely gift-funded: a discount, not consideration");
        }
        lines.add(PostingLine.credit(Accounts.DEFERRED_REVENUE, backed + credit));
        return new Post(lines, "Order paid from balance");
    }

    /**
     * Settles the refund liability in the proportion the spend recorded. The gifted part
     * settles nothing: it was never consideration, so there is no liability standing
     * against it — balance returns it to the user's gift pool and the books say nothing.
     */
    private Mono<PostingOutcome> refunded(Fact fact, Map<String, Object> payload) {
        long amount = Money.fromMinor(payload.get("amountMinor"));
        long giftRestored = Money.fromMinor(payload.get("giftRestoredMinor"));
        long net = amount - giftRestored;
        if (net <= 0) {
            return Mono.just(new Ignore("entirely gift-funded: nothing was owed"));
        }
        return creditFundedOn(fact.getAggregateId()).map(creditFunded -> {
            long creditBack = Math.min(creditFunded, net);
            long backedBack = net - creditBack;
            List<PostingLine> lines = new ArrayList<>();
            lines.add(PostingLine.debit(Accounts.REFUND_LIABILITY, net));
            if (creditBack > 0) {
                // Repays the receivable first: the user owed us this before they owned it.
                lines.add(PostingLine.credit(Accounts.RECEIVABLES, creditBack));
            }
            if (backedBack > 0) {
                lines.add(PostingLine.credit(Accounts.STORED_VALUE, backedBack));
            }
            return (PostingOutcome) new Post(lines, "Refund credited back to balance");
        });
    }

    // ---------------------------------------------------------------------- lookups

    /**
     * The cost basis frozen onto OrderPlaced at order time (D26).
     *
     * <p>Zero for orders placed before {@code unitCost} rode this event, and that is the
     * honest outcome: those sales have no recorded cost, so they book revenue with no COGS
     * rather than COGS invented from today's catalogue. It shows up as an impossible gross
     * margin in the month it happens, which is information.
     */
    @SuppressWarnings("unchecked")
    private long costOfGoods(Map<String, Object> orderPayload) {
        Object items = orderPayload.get("items");
        if (!(items instanceof List<?> list)) {
            return 0L;
        }
        long total = 0L;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object unitCost = ((Map<String, Object>) map).get("unitCost");
                if (unitCost == null) {
                    continue;
                }
                long quantity = count(((Map<String, Object>) map).get("quantity"));
                total += quantity * Money.fromDecimal(unitCost);
            }
        }
        return total;
    }

    /** How much of this order was paid with conjured money, from the stored Spent fact. */
    private Mono<Long> giftFundedOn(String orderId) {
        return spentPayload(orderId)
                .map(p -> Money.fromMinor(p.get("giftFundedMinor")))
                .defaultIfEmpty(0L);
    }

    private Mono<Long> creditFundedOn(String orderId) {
        return spentPayload(orderId)
                .map(p -> Money.fromMinor(p.get("creditFundedMinor")))
                .defaultIfEmpty(0L);
    }

    private Mono<Map<String, Object>> spentPayload(String orderId) {
        if (orderId == null) {
            return Mono.empty();
        }
        return factRepository.findByAggregateAndType(orderId, "Spent").next().map(this::parseFact);
    }

    private Mono<Boolean> wasDelivered(String orderId) {
        if (orderId == null) {
            return Mono.just(false);
        }
        return factRepository.findByAggregateAndType(orderId, "DeliveryDelivered")
                .hasElements();
    }

    private Map<String, Object> parseFact(Fact fact) {
        return parse(fact.getPayload());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return MAPPER.readValue(json, Map.class);
    }

    static String string(Object value) {
        return value == null ? null : value.toString();
    }

    /** A count of units, not an amount of money — deliberately not routed through Money. */
    private static long count(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString().trim());
    }
}
