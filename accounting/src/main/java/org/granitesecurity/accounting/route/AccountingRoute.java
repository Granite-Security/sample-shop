package org.granitesecurity.accounting.route;

import org.granitesecurity.accounting.handler.BooksHandler;
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
        @RouterOperation(
            path = "/api/accounting/journals",
            method = RequestMethod.GET,
            beanClass = BooksHandler.class,
            beanMethod = "getJournals"
        ),
        @RouterOperation(
            path = "/api/accounting/trial-balance",
            method = RequestMethod.GET,
            beanClass = BooksHandler.class,
            beanMethod = "getTrialBalance"
        ),
        @RouterOperation(
            path = "/api/accounting/periods",
            method = RequestMethod.GET,
            beanClass = BooksHandler.class,
            beanMethod = "getPeriods"
        ),
        @RouterOperation(
            path = "/api/accounting/periods/{code}/close",
            method = RequestMethod.POST,
            beanClass = BooksHandler.class,
            beanMethod = "closePeriod"
        ),
        @RouterOperation(
            path = "/api/accounting/credit-loss",
            method = RequestMethod.GET,
            beanClass = BooksHandler.class,
            beanMethod = "getCreditLoss"
        ),
        @RouterOperation(
            path = "/api/accounting/periods/{code}/estimates",
            method = RequestMethod.POST,
            beanClass = BooksHandler.class,
            beanMethod = "runEstimates"
        ),
        @RouterOperation(
            path = "/api/accounting/reconcile",
            method = RequestMethod.GET,
            beanClass = BooksHandler.class,
            beanMethod = "getReconcile"
        ),
    })
    public RouterFunction<ServerResponse> accountingRoutes(ChartHandler chartHandler,
                                                           BooksHandler booksHandler) {
        return RouterFunctions.route()
                .GET("/api/accounting/accounts", chartHandler::getChartOfAccounts)

                // The audit trail: what was booked, and the proof it adds up
                .GET("/api/accounting/journals", booksHandler::getJournals)
                .GET("/api/accounting/trial-balance", booksHandler::getTrialBalance)
                .GET("/api/accounting/reconcile", booksHandler::getReconcile)

                // The estimates: an allowance is a position as of a date, so it is read
                // here rather than bucketed into a month (§2.6)
                .GET("/api/accounting/credit-loss", booksHandler::getCreditLoss)

                // Periods. The close is the only write in this service, and it moves no
                // money — it freezes a month (D20's read-only rule still holds for amounts).
                .GET("/api/accounting/periods", booksHandler::getPeriods)
                .POST("/api/accounting/periods/{code}/close", booksHandler::closePeriod)
                .POST("/api/accounting/periods/{code}/estimates", booksHandler::runEstimates)
                .build();
    }
}
