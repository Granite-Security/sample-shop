package org.granitesecurity.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.granitesecurity.shop.client.PaymentClient;
import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OutboxEvent;
import org.granitesecurity.shop.dto.PurgeEligibility;
import org.granitesecurity.shop.dto.PurgeResult;
import org.granitesecurity.shop.repository.CustomerOrderRepository;
import org.granitesecurity.shop.repository.OrderItemRepository;
import org.granitesecurity.shop.repository.OutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the user-deletion flow needs from shop. shop is the only service
 * that can map a username to order ids — payment and delivery key on order_id
 * and have no username to match on (docs/users/blocking-users.md §6).
 */
@Service
public class UserOrderService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * The only two payment statuses that mean money actually moved. REFUNDED
     * counts because money moved in both directions and Stripe keeps both
     * records. Order status is never consulted: PAID → CANCELLED is a legal
     * transition, so a CANCELLED order may well have been paid and refunded
     * (§2.3).
     */
    private static final Set<String> MONEY_MOVED = Set.of("SUCCEEDED", "REFUNDED");

    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OutboxRepository outboxRepository;
    private final PaymentClient paymentClient;

    public UserOrderService(CustomerOrderRepository customerOrderRepository,
                            OrderItemRepository orderItemRepository,
                            OutboxRepository outboxRepository,
                            PaymentClient paymentClient) {
        this.customerOrderRepository = customerOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.outboxRepository = outboxRepository;
        this.paymentClient = paymentClient;
    }

    public Mono<PurgeEligibility> purgeEligibility(String username) {
        return customerOrderRepository.findByUsername(username)
                .map(CustomerOrder::getId)
                .collectList()
                .flatMap(orderIds -> paymentClient.statusesByOrderIds(orderIds)
                        .map(statuses -> {
                            // statuses.get(id) is null for an order with no
                            // payment row at all; Set.of().contains(null)
                            // throws, so the null check is load-bearing.
                            long paid = orderIds.stream()
                                    .map(statuses::get)
                                    .filter(status -> status != null && MONEY_MOVED.contains(status))
                                    .count();
                            return new PurgeEligibility(paid == 0, orderIds, (int) paid);
                        }));
    }

    /**
     * Deletes the user's orders and publishes OrdersPurged so payment and
     * delivery can drop their rows by order_id.
     *
     * <p>Re-checks eligibility rather than trusting the caller. profile already
     * checks before calling, but the check and the delete are separate calls
     * with a user able to place an order in between (§4.2, "the race"), and
     * deleting a paid order is exactly the outcome §9 rejected — shop and
     * Stripe diverging permanently. Refusing here costs nothing.
     */
    @Transactional
    public Mono<PurgeResult> purgeOrders(String username) {
        return purgeEligibility(username)
                .flatMap(eligibility -> {
                    if (!eligibility.eligible()) {
                        return Mono.error(new ShopException(
                                "User has " + eligibility.paidOrderCount() + " order(s) that moved money",
                                HttpStatus.CONFLICT, "Not purgeable"));
                    }
                    List<Long> orderIds = eligibility.orderIds();
                    if (orderIds.isEmpty()) {
                        return Mono.just(new PurgeResult(List.of()));
                    }
                    return orderItemRepository.deleteByOrderIdIn(orderIds)
                            .then(customerOrderRepository.deleteByIdIn(orderIds))
                            .then(outboxRepository.save(ordersPurgedEvent(username, orderIds)))
                            .thenReturn(new PurgeResult(orderIds));
                });
    }

    /** Every username that owns orders, with a count — for the orphan sweep. */
    public reactor.core.publisher.Flux<CustomerOrderRepository.OrderOwner> orderOwners() {
        return customerOrderRepository.findOrderOwners();
    }

    /**
     * Of the given order ids, the ones shop no longer has. payment and delivery
     * report the ids they hold; only shop can say which are orphans.
     */
    public Mono<List<Long>> unknownOrderIds(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return customerOrderRepository.findExistingIds(orderIds)
                .collect(java.util.stream.Collectors.toSet())
                .map(existing -> orderIds.stream().filter(id -> !existing.contains(id)).toList());
    }

    // Rides the existing outbox to orders.events — at-least-once, same as
    // OrderPlaced. A redelivery is harmless: deleting by order_id twice is a
    // no-op, which is why Phase 3 needs no dedupe table.
    private OutboxEvent ordersPurgedEvent(String username, List<Long> orderIds) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "OrdersPurged");
            payload.put("username", username);
            payload.put("orderIds", orderIds);
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            return new OutboxEvent("user", username, "OrdersPurged", json, "PENDING");
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }
}
