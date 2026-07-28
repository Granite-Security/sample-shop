package org.granitesecurity.shop.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
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

@Configuration
public class ShopSec {

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8080}")
    private String allowedOrigins;

    // See PaymentSec for why jwk-set-uri is fixed/internal while trusted-issuers
    // is a domain allow-list, replacing single-issuer-uri validation.
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
                        .pathMatchers(HttpMethod.GET, "/api/shop/products",
                                "/api/shop/products/**", "/api/shop/categories",
                                "/api/shop/greetings/**").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/webjars/swagger-ui/**").permitAll()
                        // Service-to-service only, mirroring ProfileSec's
                        // "/api/profiles/internal/**" rule. Must precede the
                        // /api/shop/users/** admin rule below, since "internal"
                        // is not a username but would match {username}.
                        .pathMatchers("/api/shop/internal/**").hasAuthority("SCOPE_internal")
                        .pathMatchers(HttpMethod.GET, "/api/shop/users/*/orders").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/shop/orders/all")
                                .hasAnyRole("ADMIN", "MANAGER")
                        .pathMatchers(HttpMethod.GET, "/api/shop/orders",
                                "/api/shop/orders/**").authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/shop/orders").authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/shop/products",
                                "/api/shop/categories").hasAnyRole("ADMIN", "MANAGER")
                        .pathMatchers(HttpMethod.PUT, "/api/shop/products/**",
                                "/api/shop/categories/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/shop/products/**",
                                "/api/shop/categories/**").hasRole("ADMIN")
                        .anyExchange().authenticated()
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

            // scopes → SCOPE_*
            authorities.addAll(scopesConverter.convert(jwt));

            // roles → ROLE_*
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
        String[] origins = allowedOrigins.split(",");
        for (String origin : origins) {
            config.addAllowedOrigin(origin.trim());
        }
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-Requested-With",
                "Accept", "Origin", "Cache-Control"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
