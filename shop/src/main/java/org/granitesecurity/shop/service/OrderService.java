package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OrderItem;
import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.dto.OrderItemResponse;
import org.granitesecurity.shop.dto.OrderResponse;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.repository.CustomerOrderRepository;
import org.granitesecurity.shop.repository.OrderItemRepository;
import org.granitesecurity.shop.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    public OrderService(CustomerOrderRepository customerOrderRepository,
                        OrderItemRepository orderItemRepository,
                        ProductRepository productRepository) {
        this.customerOrderRepository = customerOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Mono<OrderResponse> placeOrder(String username, PlaceOrderRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            return Mono.error(new ShopException("Order must contain at least one item"));
        }

        List<Long> productIds = request.items().stream()
                .map(PlaceOrderRequest.LineItem::productId)
                .toList();

        return productRepository.findAllById(productIds)
                .collectMap(Product::getId, Function.identity())
                .flatMap(productMap -> validateAndBuild(username, request, productMap))
                .flatMap(this::persistOrder);
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

        CustomerOrder order = new CustomerOrder(username, "PENDING", total);
        return Mono.just(new OrderContext(order, items, productMap));
    }

    private Mono<OrderResponse> persistOrder(OrderContext ctx) {
        return customerOrderRepository.save(ctx.order())
                .flatMap(savedOrder -> {
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

                    return orderItemRepository.saveAll(itemsWithOrderId).collectList()
                            .flatMap(savedItems -> Mono.when(stockUpdates).thenReturn(savedOrder))
                            .flatMap(order -> buildOrderResponse(order, itemsWithOrderId));
                });
    }

    public Flux<OrderResponse> getOrdersForUser(String username) {
        return customerOrderRepository.findByUsername(username)
                .flatMap(this::enrichOrder);
    }

    public Mono<OrderResponse> getOrder(Long id, String username) {
        return customerOrderRepository.findById(id)
                .switchIfEmpty(Mono.error(new ShopException("Order not found: " + id)))
                .flatMap(order -> {
                    if (!order.getUsername().equals(username)) {
                        return Mono.error(new ShopException("Order not found: " + id));
                    }
                    return enrichOrder(order);
                });
    }

    private Mono<OrderResponse> enrichOrder(CustomerOrder order) {
        return orderItemRepository.findByOrderId(order.getId())
                .map(item -> new OrderItemResponse(
                        item.getId(), item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .collectList()
                .map(items -> toOrderResponse(order, items));
    }

    private Mono<OrderResponse> buildOrderResponse(CustomerOrder order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = items.stream()
                .map(item -> new OrderItemResponse(
                        item.getId(), item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList();
        return Mono.just(toOrderResponse(order, itemResponses));
    }

    private OrderResponse toOrderResponse(CustomerOrder order, List<OrderItemResponse> items) {
        return new OrderResponse(
                order.getId(),
                order.getUsername(),
                order.getStatus(),
                order.getTotal(),
                order.getCreatedAt(),
                items
        );
    }

    private record OrderContext(CustomerOrder order, List<OrderItem> items, Map<Long, Product> productMap) {}
}
