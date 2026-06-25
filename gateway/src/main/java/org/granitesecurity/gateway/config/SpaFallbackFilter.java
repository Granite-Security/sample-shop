package org.granitesecurity.gateway.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 50)
public class SpaFallbackFilter implements WebFilter {

    private final byte[] indexHtml;

    public SpaFallbackFilter() throws IOException {
        var resource = new ClassPathResource("static/index.html");
        try (var is = resource.getInputStream()) {
            indexHtml = is.readAllBytes();
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getURI().getPath();

        if (path.startsWith("/api/")
                || path.startsWith("/assets/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/webjars/")
                || path.equals("/favicon.svg")
                || path.equals("/icons.svg")) {
            return chain.filter(exchange);
        }

        if (exchange.getRequest().getMethod() != HttpMethod.GET) {
            return chain.filter(exchange);
        }

        var response = exchange.getResponse();
        response.getHeaders().setContentType(MediaType.TEXT_HTML);
        var buffer = response.bufferFactory().wrap(indexHtml);
        return response.writeWith(Mono.just(buffer));
    }
}
