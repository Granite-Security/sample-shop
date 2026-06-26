# External Service: Calling Secured APIs

This guide explains how an external Spring Boot service can call secured APIs
proxied by the gateway, e.g. `http://localhost:8080/api/secured`.

---

## 1. Register a client on the auth-server

In `auth-server/.../SecurityConfig.java`, add a **client credentials** client
to the `registeredClientRepository()` bean:

```java
RegisteredClient externalClient = RegisteredClient.withId(UUID.randomUUID().toString())
    .clientId("external-service")
    .clientSecret("{noop}my-secret")
    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
    .scope("openid")
    .build();
```

Add it to `InMemoryRegisteredClientRepository`:

```java
return new InMemoryRegisteredClientRepository(oidcClient, spaClient, externalClient);
```

---

## 2. Get a token

```bash
curl -X POST http://localhost:8080/auth/oauth2/token \
  -H "Authorization: Basic $(echo -n external-service:my-secret | base64)" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&scope=openid"
```

Response:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiJ9...",
  "token_type": "Bearer",
  "expires_in": 300
}
```

---

## 3. Call the secured API

```bash
curl -H "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9..." \
  http://localhost:8080/api/secured
```

The gateway proxies to the downstream service, which validates the JWT.

---

## 4. Or: Spring Boot OAuth2 client auto-config

In the external service's `application.yaml`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          my-client:
            provider: my-provider
            client-id: external-service
            client-secret: my-secret
            authorization-grant-type: client_credentials
            scope: openid
        provider:
          my-provider:
            issuer-uri: http://localhost:8080/auth
```

Then use `WebClient` — Spring handles token fetching, caching, and refreshing
automatically:

```java
@Bean
WebClient securedWebClient(ReactiveClientRegistrationRepository registrations,
                           ServerOAuth2AuthorizedClientRepository authorizedClients) {
    var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(
        registrations, authorizedClients);
    oauth2.setDefaultOAuth2AuthorizedClient(true);

    return WebClient.builder()
        .baseUrl("http://localhost:8080")
        .apply(oauth2.oauth2Configuration())
        .build();
}
```

Usage:

```java
String response = securedWebClient.get()
    .uri("/api/secured")
    .retrieve()
    .bodyToMono(String.class)
    .block();
```

---

## Important notes

- The gateway **does not validate tokens** — it proxies them through. The
  downstream service (greetings, shop, payment) validates the JWT against the
  issuer `http://localhost:8080/auth`.
- Client credentials tokens carry **no user identity** — use the
  authorization code flow (like the SPA does) when you need user context.
- `{noop}my-secret` disables password encoding — fine for dev. Use
  `PasswordEncoderFactories.createDelegatingPasswordEncoder()` in production.
