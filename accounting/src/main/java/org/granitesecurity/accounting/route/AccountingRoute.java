package org.granitesecurity.accounting.route;

import org.granitesecurity.accounting.handler.ChartHandler;
import org.springdoc.core.annotations.RouterOperation;
import org.springdoc.core.annotations.RouterOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Functional routing, matching shop, payment and balance.
 *
 * <p><b>Every route needs a {@code @RouterOperation} or it is live but invisible to
 * Swagger.</b> springdoc cannot see functional routes; the same warning sits at the top
 * of BalanceRoute, and it is the reason endpoints have gone missing from the docs there.
 */
@Configuration
public class AccountingRoute {

    @Bean
    @RouterOperations({
        @RouterOperation(
            path = "/api/accounting/accounts",
            method = RequestMethod.GET,
            beanClass = ChartHandler.class,
            beanMethod = "getChartOfAccounts"
        ),
    })
    public RouterFunction<ServerResponse> accountingRoutes(ChartHandler chartHandler) {
        return RouterFunctions.route()
                .GET("/api/accounting/accounts", chartHandler::getChartOfAccounts)
                .build();
    }
}
