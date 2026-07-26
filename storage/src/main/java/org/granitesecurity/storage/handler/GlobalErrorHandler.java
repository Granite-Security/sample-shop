package org.granitesecurity.storage.handler;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.storage.service.StorageException;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
@Order(-2)
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        var result = resolve(ex);
        return writeProblem(exchange, result.status(), result.title(), result.detail());
    }

    private ProblemInfo resolve(Throwable ex) {
        if (ex instanceof StorageException se) {
            return new ProblemInfo(se.getStatus(), se.getTitle(), se.getMessage());
        }
        if (ex.getCause() instanceof StorageException se) {
            return new ProblemInfo(se.getStatus(), se.getTitle(), se.getMessage());
        }
        if (ex instanceof ResponseStatusException rse) {
            ProblemDetail body = rse.getBody();
            return new ProblemInfo(
                    HttpStatus.valueOf(body.getStatus()), body.getTitle(), body.getDetail());
        }
        return new ProblemInfo(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error", "An unexpected error occurred");
    }

    private Mono<Void> writeProblem(ServerWebExchange exchange, HttpStatus status,
                                     String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(problem);
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (Exception e) {
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String fallback = "{\"type\":\"about:blank\",\"title\":\"" + title
                    + "\",\"status\":" + status.value()
                    + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}";
            return exchange.getResponse()
                    .writeWith(Mono.just(
                            exchange.getResponse().bufferFactory().wrap(fallback.getBytes())));
        }
    }

    private record ProblemInfo(HttpStatus status, String title, String detail) {}
}
