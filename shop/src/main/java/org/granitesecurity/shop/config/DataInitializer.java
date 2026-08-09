package org.granitesecurity.shop.config;

import org.granitesecurity.shop.service.CatalogService;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    CommandLineRunner initData(CatalogService catalogService) {
        return args -> catalogService.getAllCategories(0, 1)
                .flatMap(existing -> {
                    if (existing.total() > 0) {
                        log.info("Database already seeded, skipping initialization");
                        return Mono.empty();
                    }
                    return seedData(catalogService);
                })
                .block();
    }

    private Mono<Void> seedData(CatalogService catalogService) {
        var electronics = catalogService.createCategory(
                new CreateCategoryRequest("Electronics", "Gadgets and devices"));
        var clothing = catalogService.createCategory(
                new CreateCategoryRequest("Clothing", "Apparel and accessories"));
        var home = catalogService.createCategory(
                new CreateCategoryRequest("Home & Garden", "Furniture and decor"));

        return Mono.zip(electronics, clothing, home).flatMap(categories -> {
            var catEl = categories.getT1().id();
            var catCl = categories.getT2().id();
            var catHo = categories.getT3().id();

            var products = List.of(
                    new CreateProductRequest("Wireless Headphones", "Noise-cancelling Bluetooth headphones",
                            new BigDecimal("149.99"), 50, catEl,
                            "https://picsum.photos/seed/headphones/400/400", null, false),
                    new CreateProductRequest("Smart Watch", "Fitness tracker with heart rate monitor",
                            new BigDecimal("249.99"), 30, catEl,
                            "https://picsum.photos/seed/smartwatch/400/400", null, false),
                    new CreateProductRequest("USB-C Hub", "7-in-1 multiport adapter",
                            new BigDecimal("39.99"), 100, catEl,
                            "https://picsum.photos/seed/usbhub/400/400", null, false),
                    new CreateProductRequest("Cotton T-Shirt", "Soft 100% organic cotton",
                            new BigDecimal("29.99"), 200, catCl,
                            "https://picsum.photos/seed/tshirt/400/400", null, false),
                    new CreateProductRequest("Denim Jacket", "Classic blue denim",
                            new BigDecimal("89.99"), 40, catCl,
                            "https://picsum.photos/seed/jacket/400/400", null, false),
                    new CreateProductRequest("Running Shoes", "Lightweight mesh sneakers",
                            new BigDecimal("119.99"), 60, catCl,
                            "https://picsum.photos/seed/shoes/400/400", null, false),
                    new CreateProductRequest("Indoor Plant Pot", "Ceramic pot with bamboo stand",
                            new BigDecimal("45.00"), 80, catHo,
                            "https://picsum.photos/seed/plantpot/400/400", null, false),
                    new CreateProductRequest("Scented Candle Set", "Set of 3 soy wax candles",
                            new BigDecimal("34.99"), 120, catHo,
                            "https://picsum.photos/seed/candles/400/400", null, false),
                    new CreateProductRequest("Throw Blanket", "Soft microfiber 150x200 cm",
                            new BigDecimal("54.99"), 90, catHo,
                            "https://picsum.photos/seed/blanket/400/400", null, false)
            );

            return Mono.when(products.stream()
                    .map(catalogService::createProduct)
                    .toList());
        }).then(Mono.fromRunnable(() -> log.info("Seed data inserted")));
    }

}
