package org.granitesecurity.balance.route;

import org.granitesecurity.balance.handler.AdminBalanceHandler;
import org.granitesecurity.balance.handler.BalanceHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class BalanceRoute {

    @Bean
    public RouterFunction<ServerResponse> balanceRoutes(BalanceHandler balanceHandler,
                                                        AdminBalanceHandler adminHandler) {
        return RouterFunctions.route()
                .GET("/api/balance/me", balanceHandler::getMyBalance)
                .GET("/api/balance/me/transactions", balanceHandler::getMyTransactions)
                // Admin routes are gated in BalanceSec by path prefix, so they must
                // stay under /admin/ — a gift endpoint anywhere else would be
                // authenticated but not role-checked.
                .POST("/api/balance/admin/gifts", adminHandler::gift)
                .GET("/api/balance/admin/reconcile", adminHandler::reconcile)
                .build();
    }
}
