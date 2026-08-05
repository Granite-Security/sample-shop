package org.granitesecurity.balance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Copied from ProfileSec, including the jwk-set-uri / trusted-issuers split
 * (see PaymentSec for why that replaced single-issuer-uri validation).
 *
 * <p>This service moves money, so the rules below are the whole authorization
 * model — there is no second check further in. In particular {@code /admin/**}
 * is where gifting is actually authorized, not the UI that hides the button
 * (docs/finance/finance.md D11).
 */
@Configuration
public class BalanceSec {

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8080}")
    private String allowedOrigins;

    @Value("${jwt.jwk-set-uri:http://localhost:9090/auth/oauth2/jwks}")
    private String jwkSetUri;

    @Value("${jwt.trusted-issuers:http://localhost:8080/auth}")
    private String trustedIssuersRaw;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchange -> exchange
                        // Service-to-service only: this is what payment's
                        // BalanceProvider calls to debit an order. A user token
                        // must never reach it, or anyone could spend anyone's
                        // balance (docs/finance/finance.md §7.2).
                        .pathMatchers("/api/balance/internal/**").hasAuthority("SCOPE_internal")
                        // Creating money out of nothing. Ordered before /me/**
                        // and the catch-all so neither can shadow it.
                        .pathMatchers("/api/balance/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/balance/me", "/api/balance/me/**").authenticated()
                        // Anything else under /api/balance is authenticated by
                        // default rather than open: a new endpoint added without
                        // a matching rule should fail closed, not fall through.
                        .pathMatchers("/api/balance/**").authenticated()
                        .anyExchange().permitAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();

        Set<String> trustedIssuers = new HashSet<>();
        Arrays.stream(trustedIssuersRaw.split(",")).map(String::trim).forEach(trustedIssuers::add);

        OAuth2TokenValidator<Jwt> issuerValidator = jwt -> {
            String iss = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
            if (iss != null && trustedIssuers.contains(iss)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_issuer", "The iss claim is not trusted: " + iss, null));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuerValidator));
        return decoder;
    }

    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

        Converter<Jwt, Collection<GrantedAuthority>> combined = jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            authorities.addAll(scopesConverter.convert(jwt));

            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .forEach(authorities::add);
            }
            return authorities;
        };

        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ReactiveJwtGrantedAuthoritiesConverterAdapter(combined));
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        for (String origin : allowedOrigins.split(",")) {
            config.addAllowedOrigin(origin.trim());
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With",
                "Accept", "Origin", "Cache-Control"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
