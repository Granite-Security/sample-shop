package org.granitesecurity.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import java.net.URI;

@Configuration
@EnableWebFluxSecurity
public class GateSec {

    @Value("${app.spa-origin:http://localhost:5173}")
    private String spaOrigin;

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
                        .pathMatchers("/").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2Login(Customizer.withDefaults())
//                .oauth2Login(oauth2 -> oauth2
//                        .authenticationSuccessHandler(spaRedirectHandler())
//                )
                .oauth2Client(Customizer.withDefaults())
                .build();
    }

    private ServerAuthenticationSuccessHandler spaRedirectHandler() {
        return (webFilterExchange, authentication) -> {
            ServerHttpResponse response = webFilterExchange.getExchange().getResponse();
            response.setStatusCode(HttpStatus.FOUND);
            response.getHeaders().setLocation(URI.create(spaOrigin + "/"));
            return response.setComplete();
        };
    }

}
