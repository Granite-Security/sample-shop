package org.granitesecurity.delivery.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

@Configuration
@EnableWebFluxSecurity
public class DeliverySec {

    private final ReactiveJwtAuthenticationConverter jwtAuthenticationConverter;
    private final ReactiveJwtDecoder jwtDecoder;

    public DeliverySec(ReactiveJwtAuthenticationConverter jwtAuthenticationConverter, ReactiveJwtDecoder jwtDecoder) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.jwtDecoder = jwtDecoder;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/api/**"))
                .authorizeExchange(exchanges -> exchanges
                        // Service-to-service only, mirroring ProfileSec's
                        // "/api/profiles/internal/**" rule.
                        .pathMatchers("/api/delivery/internal/**").hasAuthority("SCOPE_internal")
                        .pathMatchers(HttpMethod.PUT, "/api/delivery/{orderId}/status").hasAnyRole("ADMIN", "MANAGER")
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                )
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}
