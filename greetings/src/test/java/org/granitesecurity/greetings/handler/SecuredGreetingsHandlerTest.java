package org.granitesecurity.greetings.handler;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.server.EntityResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecuredGreetingsHandlerTest {

    private final SecuredGreetingsHandler handler = new SecuredGreetingsHandler();

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeRolesAndGrants() {
        ServerRequest request = mock(ServerRequest.class);
        Authentication authentication = mock(Authentication.class);

        when(authentication.getName()).thenReturn("testuser");
        when(authentication.getCredentials()).thenReturn(mock(Jwt.class));
        when(authentication.getAuthorities()).thenAnswer(inv -> List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("SCOPE_read")
        ));

        doReturn(Mono.just(authentication)).when(request).principal();

        Mono<ServerResponse> responseMono = handler.respondWithSecuredGreeting(request);

        StepVerifier.create(responseMono)
                .assertNext(response -> {
                    assert response.statusCode().is2xxSuccessful();
                    if (response instanceof EntityResponse) {
                        Object entity = ((EntityResponse<String>) response).entity();
                        String body = entity.toString();
                        assert body.contains("Hello, testuser!");
                        assert body.contains("ROLE_USER");
                        assert body.contains("SCOPE_read");
                    }
                })
                .verifyComplete();
    }
}
