package org.granitesecurity.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class GateSec {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/api/greetings/**").permitAll()
                        .pathMatchers("/api/shop/products/**").permitAll()
                        .pathMatchers("/api/shop/categories").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/webjars/swagger-ui/**").permitAll()
                        .pathMatchers("/api/user/me").permitAll()
                        .pathMatchers("/assets/**", "/favicon.svg", "/icons.svg").permitAll()
                        .pathMatchers("/error").permitAll()
                        .matchers(spaMatcher()).permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2Login(Customizer.withDefaults())
                .oauth2Client(Customizer.withDefaults())
                .build();
    }

    private ServerWebExchangeMatcher spaMatcher() {
        return exchange -> {
            var path = exchange.getRequest().getURI().getPath();
            if (exchange.getRequest().getMethod() == HttpMethod.GET
                    && !path.startsWith("/api/")
                    && !path.startsWith("/assets/")
                    && !path.startsWith("/v3/api-docs")
                    && !path.startsWith("/swagger-ui")
                    && !path.startsWith("/webjars/")
                    && !path.equals("/favicon.svg")
                    && !path.equals("/icons.svg")
                    && !path.equals("/error")) {
                return MatchResult.match();
            }
            return MatchResult.notMatch();
        };
    }

}
