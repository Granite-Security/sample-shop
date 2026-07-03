1. Auth-server: Register a public SPA client
   File: auth-server/src/main/java/org/granitesecurity/authserver/SecurityConfig.java
   Add a second RegisteredClient to the registeredClientRepository() bean:
   RegisteredClient spaClient = RegisteredClient.withId(UUID.randomUUID().toString())
   .clientId("spa-client")
   .clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // public client, no secret
   .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
   .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
   .redirectUri("http://localhost:5173/callback")
   .scope(OidcScopes.OPENID)
   .scope(OidcScopes.PROFILE)
   .scope(StandardClaimNames.EMAIL)
   .clientSettings(ClientSettings.builder()
   .requireAuthorizationConsent(true)
   .requireProofKey(true)  // force PKCE
   .build())
   .build();
   Key points:
   •
   CLIENT_SECRET_BASIC → NONE (public client)
   •
   requireProofKey(true) → PKCE required, no secret needed
   •
   Redirect URI: http://localhost:5173/callback (SPA dev server)
   We'll also add an environment variable for the redirect URI so production can override it (similar to the existing OIDC_CLIENT_REDIRECT_URI pattern).

2. Gateway: Remove OAuth2 client/login, simplify
   The gateway no longer manages OAuth2 sessions or token relay — it's a pure proxy. Bearer tokens from the SPA pass through naturally.
   File: gateway/src/main/java/org/granitesecurity/gateway/config/GateSec.java
   •
   Remove .oauth2Login(Customizer.withDefaults())
   •
   Remove .oauth2Client(Customizer.withDefaults())
   •
   Auth paths (/auth/**) stay permitAll (needed for OIDC discovery, authorize, token, JWKS)
   File: gateway/src/main/java/org/granitesecurity/gateway/config/RouterConfig.java
   •
   Remove TokenRelayGatewayFilterFactory parameter from gatewayRouter() method
   •
   Remove .filters(f -> f.filter(tokenRelay.apply())) from all routes — Bearer token headers