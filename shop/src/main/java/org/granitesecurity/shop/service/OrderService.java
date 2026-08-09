package org.granitesecurity.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OrderItem;
import org.granitesecurity.shop.domain.OrderStatus;
import org.granitesecurity.shop.domain.OutboxEvent;
import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.dto.OrderItemResponse;
import org.granitesecurity.shop.dto.OrderResponse;
import org.granitesecurity.shop.dto.PagedResult;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.repository.CustomerOrderRepository;
import org.granitesecurity.shop.repository.OrderItemRepository;
import org.granitesecurity.shop.repository.OutboxRepository;
import org.granitesecurity.shop.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Consumed by profile, which turns it into an in-app message to admin. */
    static final String SHOP_NOTIFICATIONS_TOPIC = "shop.notifications";
    static final String ORDER_PLACED_NOTICE_EVENT = "OrderPlacedNotice";

    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final OutboxRepository outboxRepository;

    /**
     * The currency new orders are priced in. Must match payment's shop-currency —
     * shop prices the order, payment charges it, and nothing converts between them.
     */
    @Value("${shop.currency:CHF}")
    private String shopCurrency;

    public OrderService(CustomerOrderRepository customerOrderRepository,
                        OrderItemRepository orderItemRepository,
                        ProductRepository productRepository,
                        OutboxRepository outboxRepository) {
        this.customerOrderRepository = customerOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Mono<OrderResponse> placeOrder(String username, PlaceOrderRequest request) {
        return placeOrder(username, request, null);
    }

    /**
     * @param storefrontOrigin where the shopper is browsing, or null when it could not be
     *                         derived. Recorded and published so payment can redirect back
     *                         to the right domain (docs/bugs/redirects.md §4.1).
     */
    @Transactional
    public Mono<OrderResponse> placeOrder(String username, PlaceOrderRequest request,
                                          String storefrontOrigin) {
        if (request.items() == null || request.items().isEmpty()) {
            return Mono.error(new ShopException("Order must contain at least one item"));
        }

        if (request.provider() == null || request.provider().isBlank()) {
            return Mono.error(new ShopException(
                    "Order must name a payment provider; see GET /api/payments/providers"));
        }

        List<Long> productIds = request.items().stream()
                .map(PlaceOrderRequest.LineItem::productId)
                .toList();

        return productRepository.findAllById(productIds)
                .collectMap(Product::getId, Function.identity())
                .flatMap(productMap -> validateAndBuild(username, request, productMap))
                .doOnNext(ctx -> ctx.order().setStorefrontOrigin(storefrontOrigin))
                .flatMap(ctx -> persistOrder(ctx));
    }

    private Mono<OrderContext> validateAndBuild(String username, PlaceOrderRequest request,
                                                 Map<Long, Product> productMap) {
        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();

        for (PlaceOrderRequest.LineItem line : request.items()) {
            Product product = productMap.get(line.productId());
            if (product == null) {
                return Mono.error(new ShopException("Product not found: " + line.productId()));
            }
            if (product.getStock() < line.quantity()) {
                return Mono.error(new ShopException(
                        "Insufficient stock for product '" + product.getName()
                                + "' (available: " + product.getStock() + ", requested: " + line.quantity() + ")"));
            }

            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(line.quantity()));
            total = total.add(lineTotal);

            items.add(new OrderItem(null, line.productId(), line.quantity(), product.getPrice()));
        }

        PlaceOrderRequest.DeliveryAddress addr = request.address();
        String addrLine2 = addr.addressLine2() != null ? addr.addressLine2() : "";
        String state = addr.state() != null ? addr.state() : "";
        CustomerOrder order = new CustomerOrder(username, OrderStatus.PENDING.name(), total, shopCurrency,
                addr.recipientName(), addr.addressLine1(), addrLine2, addr.city(), state, addr.zipCode(), addr.country());
        return Mono.just(new OrderContext(order, items, productMap, request.provider()));
    }

    private Mono<OrderResponse> persistOrder(OrderContext ctx) {
        return customerOrderRepository.save(ctx.order())
                .flatMap(savedOrder -> saveOrderAndReturnResponse(ctx, savedOrder));
    }

    private Mono<OrderResponse> saveOrderAndReturnResponse(OrderContext ctx, CustomerOrder savedOrder) {
        List<OrderItem> itemsWithOrderId = ctx.items().stream()
                .map(item -> new OrderItem(
                        savedOrder.getId(),
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice()))
                .toList();

        List<Mono<Product>> stockUpdates = ctx.items().stream()
                .map(item -> {
                    Product product = ctx.productMap().get(item.getProductId());
                    product.setStock(product.getStock() - item.getQuantity());
                    return productRepository.save(product);
                })
                .toList();

        Mono<CustomerOrder> afterSave = orderItemRepository.saveAll(itemsWithOrderId).collectList()
                .flatMap(savedItems -> Mono.when(stockUpdates).thenReturn(savedOrder));

        return afterSave.flatMap(order -> {
            CustomerOrder co = order;
            OutboxEvent outbox = createOutboxEvent(co, itemsWithOrderId, ctx.provider());
            // Second row, second topic, same transaction as the order. Not
            // REQUIRES_NEW or NESTED: a notice that can commit while the order rolls
            // back would tell admin about an order that does not exist. "Desirable,
            // not critical" is handled by it being an outbox row — if Kafka is down
            // the relay keeps retrying and nothing blocks checkout.
            OutboxEvent notice = createOrderNoticeEvent(co);
            return outboxRepository.save(outbox)
                    .then(outboxRepository.save(notice))
                    .flatMap(saved -> buildOrderResponse(co, itemsWithOrderId, null));
        });
    }

    private OutboxEvent createOutboxEvent(CustomerOrder order, List<OrderItem> items, String provider) {
        try {
            String payload = OBJECT_MAPPER.writeValueAsString(buildPayload(order, items, provider));
            return new OutboxEvent("order", String.valueOf(order.getId()), "OrderPlaced", payload, "PENDING");
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }

    /**
     * "This user placed an order", for the admin notice profile turns into an in-app
     * message. Deliberately not a copy of OrderPlaced: admin is told <em>that</em>
     * someone ordered, not what they bought, so the items, address and total stay on
     * the topic the services that need them read.
     */
    private OutboxEvent createOrderNoticeEvent(CustomerOrder order) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", ORDER_PLACED_NOTICE_EVENT);
            payload.put("username", order.getUsername());
            // Not shown to admin. orderId is the idempotency key profile dedupes on —
            // delivery is at-least-once, and without it a redelivery is a second
            // message in the inbox. occurredAt is what lets a consumer that has been
            // offline drop a replay instead of announcing week-old orders.
            payload.put("orderId", order.getId());
            payload.put("occurredAt", Instant.now().toString());
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            return new OutboxEvent("order", String.valueOf(order.getId()), ORDER_PLACED_NOTICE_EVENT, json,
                    "PENDING", SHOP_NOTIFICATIONS_TOPIC);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }

    private Map<String, Object> buildPayload(CustomerOrder order, List<OrderItem> items, String provider) {
        List<Map<String, Object>> itemList = items.stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", item.getProductId());
            m.put("quantity", item.getQuantity());
            m.put("unitPrice", item.getUnitPrice());
            return m;
        }).toList();

        Map<String, Object> addressMap = new LinkedHashMap<>();
        addressMap.put("recipientName", order.getRecipientName());
        addressMap.put("addressLine1", order.getAddressLine1());
        addressMap.put("addressLine2", order.getAddressLine2());
        addressMap.put("city", order.getCity());
        addressMap.put("state", order.getState());
        addressMap.put("zipCode", order.getZipCode());
        addressMap.put("country", order.getCountry());

        Map<String, Object> payload = new LinkedHashMap<>();
        // Tagged like every other event on orders.events. It used to be the untagged
        // one — the event that existed first — which meant consumers reached it by
        // falling through the branches for the tagged types, and so read anything they
        // did not recognise as an order. Consumers still accept an absent eventType
        // until no untagged message can be in flight; see shop/README.md § Events.
        payload.put("eventType", "OrderPlaced");
        payload.put("orderId", order.getId());
        payload.put("username", order.getUsername());
        payload.put("items", itemList);
        payload.put("total", order.getTotal());
        // Currency travels with the amount: payment must charge what shop priced, not
        // whatever its own config currently says.
        payload.put("currency", order.getCurrency());
        // Always present: placeOrder rejects an order that names none.
        payload.put("provider", provider);
        // Where the shopper was browsing. payment has no request of its own to read it
        // from, and without it a redirect payment returns to the wrong domain
        // (docs/bugs/redirects.md §4.1). Null for orders placed by a caller that sent
        // nothing to derive it from; payment falls back to its configured origin.
        payload.put("storefrontOrigin", order.getStorefrontOrigin());
        payload.put("orderedAt", Instant.now().toString());
        payload.put("address", addressMap);
        return payload;
    }

    public Mono<PagedResult<OrderResponse>> getOrdersForUser(String username, int page, int size) {
        long offset = (long) page * size;
        Mono<Long> count = customerOrderRepository.countByUsername(username);
        Flux<OrderResponse> items = customerOrderRepository.findByUsernamePaged(username, size, offset)
                .flatMapSequential(this::enrichOrder);
        return count.zipWith(items.collectList())
                .map(tuple -> new PagedResult<>(tuple.getT2(), tuple.getT1(), page, size));
    }

    public Mono<PagedResult<OrderResponse>> getAllOrders(int page, int size) {
        long offset = (long) page * size;
        Mono<Long> count = customerOrderRepository.count();
        Flux<OrderResponse> items = customerOrderRepository.findAllPaged(size, offset)
                .flatMapSequential(this::enrichOrder);
        return count.zipWith(items.collectList())
                .map(tuple -> new PagedResult<>(tuple.getT2(), tuple.getT1(), page, size));
    }

    public Mono<OrderResponse> getOrder(Long id, String username, boolean isAdmin) {
        return customerOrderRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new ShopException("Order not found: " + id, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(order -> {
                    if (!isAdmin && !order.getUsername().equals(username)) {
                        return Mono.error(
                                new ShopException("Order not found: " + id, HttpStatus.NOT_FOUND, "Not Found"));
                    }
                    return enrichOrder(order);
                });
    }

    @Transactional
    public Mono<OrderResponse> requestRefund(Long orderId, String username, boolean isAdmin) {
        return customerOrderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new ShopException("Order not found: " + orderId, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(order -> {
                    if (!isAdmin && !order.getUsername().equals(username)) {
                        return Mono.error(new ShopException(
                                "Order not found: " + orderId, HttpStatus.NOT_FOUND, "Not Found"));
                    }
                    OrderStatus current = OrderStatus.valueOf(order.getStatus());
                    if (isAdmin) {
                        if (current == OrderStatus.RETURNED || current == OrderStatus.REIMBURSED) {
                            return Mono.error(new ShopException(
                                    "Refund already requested", HttpStatus.CONFLICT, "Refund not eligible"));
                        }
                        if (current != OrderStatus.PAID && current != OrderStatus.SHIPPED
                                && current != OrderStatus.DELIVERED) {
                            return Mono.error(new ShopException(
                                    "Order was never successfully paid", HttpStatus.CONFLICT, "Refund not eligible"));
                        }
                    } else if (current != OrderStatus.SHIPPED || !"FAILED".equals(order.getDeliveryStatus())) {
                        return Mono.error(new ShopException(
                                "Order is not eligible for a refund", HttpStatus.CONFLICT, "Refund not eligible"));
                    }
                    order.setStatus(current.transitionTo(OrderStatus.RETURNED).name());
                    order.setUpdatedAt(Instant.now());
                    return customerOrderRepository.save(order)
                            .flatMap(saved -> outboxRepository.save(createRefundOutboxEvent(saved))
                                    .then(enrichOrder(saved)));
                });
    }

    public Mono<Void> updateDeliveryStatus(Long orderId, String status) {
        return customerOrderRepository.findById(orderId)
                .flatMap(order -> {
                    if (status.equals(order.getDeliveryStatus())) {
                        return Mono.empty();
                    }
                    order.setDeliveryStatus(status);
                    order.setUpdatedAt(Instant.now());
                    return customerOrderRepository.save(order);
                })
                .then();
    }

    private OutboxEvent createRefundOutboxEvent(CustomerOrder order) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "RefundRequested");
            payload.put("orderId", order.getId());
            payload.put("total", order.getTotal());
            payload.put("username", order.getUsername());
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            return new OutboxEvent("order", String.valueOf(order.getId()), "RefundRequested", json, "PENDING");
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }

    public Mono<Void> updateOrderStatus(Long orderId, OrderStatus targetStatus) {
        return customerOrderRepository.findById(orderId)
                .flatMap(order -> {
                    OrderStatus current = OrderStatus.valueOf(order.getStatus());
                    if (!current.canTransitionTo(targetStatus)) {
                        return Mono.empty();
                    }
                    order.setStatus(targetStatus.name());
                    order.setUpdatedAt(Instant.now());
                    return customerOrderRepository.save(order).then();
                });
    }

    private Mono<OrderResponse> enrichOrder(CustomerOrder order) {
        return orderItemRepository.findByOrderId(order.getId())
                .collectList()
                .flatMap(items -> resolveProductNames(items)
                        .map(nameMap -> toOrderResponse(order, items.stream()
                                .map(item -> toItemResponse(item, nameMap))
                                .toList(), null)));
    }

    private Mono<OrderResponse> buildOrderResponse(CustomerOrder order, List<OrderItem> items, String clientSecret) {
        return resolveProductNames(items)
                .map(nameMap -> toOrderResponse(order, items.stream()
                        .map(item -> toItemResponse(item, nameMap))
                        .toList(), clientSecret));
    }

    private OrderItemResponse toItemResponse(OrderItem item, Map<Long, String> productNames) {
        return new OrderItemResponse(
                item.getId(),
                item.getProductId(),
                productNames.getOrDefault(item.getProductId(), "Unknown"),
                item.getQuantity(),
                item.getUnitPrice());
    }

    private Mono<Map<Long, String>> resolveProductNames(List<OrderItem> items) {
        List<Long> productIds = items.stream()
                .map(OrderItem::getProductId)
                .distinct()
                .toList();
        return productRepository.findAllById(productIds)
                .collectMap(Product::getId, Product::getName);
    }

    private OrderResponse toOrderResponse(CustomerOrder order, List<OrderItemResponse> items, String clientSecret) {
        return new OrderResponse(
                order.getId(),
                order.getUsername(),
                order.getStatus(),
                order.getTotal(),
                order.getCurrency(),
                order.getCreatedAt(),
                items,
                // provider/providerPayload/clientSecret are payment's to know, not shop's:
                // the SPA fetches them from GET /api/payments/intent/{orderId} and merges
                // them into this object client-side. They have always been null on the wire.
                null,
                null,
                clientSecret,
                new OrderResponse.DeliveryAddress(
                        order.getRecipientName(),
                        order.getAddressLine1(),
                        order.getAddressLine2(),
                        order.getCity(),
                        order.getState(),
                        order.getZipCode(),
                        order.getCountry()
                )
        );
    }

    /**
     * @param provider the shopper's chosen payment provider, never null — placeOrder
     *                 rejects an order without one. Carried on the context rather than
     *                 the order row: it is an instruction to payment, and payment owns
     *                 which provider a payment ended up on.
     */
    private record OrderContext(CustomerOrder order, List<OrderItem> items,
                                Map<Long, Product> productMap, String provider) {}
}
