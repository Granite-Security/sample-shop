package org.granitesecurity.authserver;


import com.nimbusds.jose.jwk.JWKSet;
import org.granitesecurity.authserver.user.GoogleOidcUserService;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.oauth2.gateway-client.secret:{noop}secret}")
    private String gatewayClientSecret;

    @Value("${app.oauth2.gateway-client.redirect-uri:http://localhost:8080/login/oauth2/code/oidc-client}")
    private String gatewayClientRedirectUri;

    @Value("${app.oauth2.gateway-client.post-logout-redirect-uri:http://localhost:8080/}")
    private String gatewayClientPostLogoutRedirectUri;

    // Blank/unset on purpose in production (see AUTH_SERVER_ISSUER in
    // k8s config): Spring Authorization Server derives the issuer per-request
    // (scheme+host+context-path, honoring X-Forwarded-*) when
    // AuthorizationServerSettings.issuer is never set, which is what lets one
    // auth-server instance serve two public domains at once. kind/compose keep
    // a fixed value here since they only ever have one domain.
    @Value("${spring.security.oauth2.authorizationserver.issuer:}")
    private String issuer;

    @Value("${app.oauth2.spa-client-shop.redirect-uri:${app.oauth2.spa-client.redirect-uri:http://localhost:5173/callback}}")
    private String spaClientShopRedirectUri;

    @Value("${app.oauth2.spa-client-shop.post-logout-redirect-uri:${app.oauth2.spa-client.post-logout-redirect-uri:http://localhost:5173}}")
    private String spaClientShopPostLogoutRedirectUri;

    @Value("${app.oauth2.spa-client-chocolate.redirect-uri:http://localhost:5173/callback}")
    private String spaClientChocolateRedirectUri;

    @Value("${app.oauth2.spa-client-chocolate.post-logout-redirect-uri:http://localhost:5173}")
    private String spaClientChocolatePostLogoutRedirectUri;

    @Value("${app.oauth2.external-client.secret:{noop}my-secret}")
    private String externalClientSecret;

    @Value("${app.oauth2.internal-service.secret:{noop}internal-secret}")
    private String internalClientSecret;

    // Explicit env var rather than relaxed binding on the property name:
    // "identity-admin" would relax to IDENTITYADMIN, which nobody guesses.
    @Value("${IDENTITY_ADMIN_CLIENT_SECRET_ENCODED:{noop}identity-admin-secret}")
    private String identityAdminClientSecret;

    @Value("${GOOGLE_CLIENT_ID:google-client-id}")
    private String googleClientId;

    @Value("${GOOGLE_CLIENT_SECRET:google-client-secret}")
    private String googleClientSecret;

    @Value("${GOOGLE_REDIRECT_URI:{baseUrl}/login/oauth2/code/{registrationId}}")
    private String googleRedirectUri;

    private static KeyPair generateRsaKey() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return keyPair;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) {

        http
                .oauth2AuthorizationServer((authorizationServer) -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer
                            .oidc(Customizer.withDefaults());    // Enable OpenID Connect 1.0
                })
                .authorizeHttpRequests((authorize) ->
                        authorize
                                .anyRequest().authenticated()
                )
                // Show the login page when not authenticated from the
                // authorization endpoint so users can choose form or Google login.
                // Relative path (not an absolute issuer + "/login" URL) since the
                // issuer itself is now request-derived and single-domain-only would
                // be wrong for whichever domain didn't originate the request.
                // Spring's DefaultRedirectStrategy prepends the request's own
                // context path ("/auth") to a relative target, so this must NOT
                // include "/auth" itself or the redirect doubles up to "/auth/auth/login".
                .exceptionHandling((exceptions) -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                );

        return http.build();
    }

    // Bearer-token-authenticated, stateless — validates the same self-issued
    // JWTs auth-server itself hands out, using the existing jwtDecoder bean
    // below (built from the same in-process signing key). No new decoder or
    // trusted-issuers allow-list needed here: unlike downstream resource
    // servers (profile, storage, ...), auth-server validating its own tokens
    // doesn't need to reason about which public domain issued them.
    @Bean
    @Order(2)
    public SecurityFilterChain accountApiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {
        http
                .securityMatcher("/api/me/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)));

        return http.build();
    }

    // The internal user-administration API, called only by profile (which is
    // where ROLE_ADMIN is actually enforced — see docs/users/blocking-users.md
    // §3/§D4). auth-server understands no roles here on purpose: its JWT chains
    // install no JwtAuthenticationConverter, so hasRole('ADMIN') would silently
    // deny everyone. The gate is the dedicated identity.admin scope, which only
    // the identity-admin client can obtain; a SCOPE_internal token must get 403.
    @Bean
    @Order(3)
    public SecurityFilterChain internalUserApiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {
        http
                .securityMatcher("/api/internal/users/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().hasAuthority("SCOPE_identity.admin"))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)));

        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, GoogleOidcUserService googleOidcUserService)
            throws Exception {
        http
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/register", "/api/password-reset/request",
                                "/api/password-reset/confirm").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                // These endpoints are all unauthenticated, create/act on a
                // principal with no session or ambient authority for a forged
                // request to ride on, so it's safe to exempt them from CSRF —
                // same reasoning as /api/register already documented here.
                .csrf((csrf) -> csrf.ignoringRequestMatchers("/api/register", "/api/password-reset/request",
                        "/api/password-reset/confirm"))
                // Form login handles the redirect to the login page from the
                // authorization server filter chain
                .formLogin((form) -> form.successHandler(siteRootSuccessHandler()))
                .oauth2Login((oauth2) -> oauth2
                        .redirectionEndpoint((redirection) -> redirection
                                .baseUri("/login/oauth2/code/*")
                        )
                        .userInfoEndpoint((userInfo) -> userInfo
                                .oidcUserService(googleOidcUserService)
                                .userAuthoritiesMapper(oAuth2LoginAuthoritiesMapper())
                        )
                );

        return http.build();
    }

    private GrantedAuthoritiesMapper oAuth2LoginAuthoritiesMapper() {
        return (authorities) -> {
            Set<GrantedAuthority> mappedAuthorities = new HashSet<>(authorities);
            mappedAuthorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                    .issuedAt(Instant.now())
                    .build());
            return mappedAuthorities;
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Where a successful form login lands.
     *
     * <p>Spring's default sends the user to the request they were originally after,
     * falling back to {@code /} when there isn't one — and that fallback is resolved
     * against the servlet context path, which here is {@code /auth}. So a login typed
     * straight into {@code /auth/login} succeeded and then redirected to {@code /auth/},
     * which this application does not map: 302, then 404, looking for all the world
     * like the password was rejected.
     *
     * <p>{@code SavedRequestAware} keeps the important case intact — arriving via
     * {@code /oauth2/authorize} still resumes that flow, which is how the SPAs log in.
     * Only the fallback changes, and {@code contextRelative} makes {@code /} mean the
     * host root rather than the context root.
     *
     * <p>Host-relative on purpose: the same auth-server is reached through
     * granite-security.org and sichocolate.com, so this returns the shopper to
     * whichever site they came from with no per-domain configuration, and without
     * echoing a caller-supplied URL back as a redirect.
     */
    private AuthenticationSuccessHandler siteRootSuccessHandler() {
        DefaultRedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
        redirectStrategy.setContextRelative(true);
        // 303, not the default 302. A 302 only *conventionally* turns a POST into a
        // GET; the spec does not require it, and clients that preserve the method
        // re-POST to the redirect target. The target here is the SPA served by
        // nginx, which answers POST with 405 — so a login that succeeded still
        // ended on an error page. 303 See Other mandates the GET.
        redirectStrategy.setStatusCode(HttpStatus.SEE_OTHER);
        SavedRequestAwareAuthenticationSuccessHandler handler =
                new SavedRequestAwareAuthenticationSuccessHandler();
        handler.setRedirectStrategy(redirectStrategy);
        handler.setDefaultTargetUrl("/");
        return handler;
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        RegisteredClient oidcClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("oidc-client")
                .clientSecret(gatewayClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(gatewayClientRedirectUri)
//                .redirectUri("http://localhost:5173/login/oauth2/code/oidc-client")
                .redirectUri("https://oauth.pstmn.io/v1/callback")  // add this
                .postLogoutRedirectUri(gatewayClientPostLogoutRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(StandardClaimNames.EMAIL)
                // First-party client: no consent screen, the requested scopes
                // (openid/profile/email) are granted on login.
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .build();

        // Two SPA clients, not one client with two redirect URIs: each domain's
        // frontend gets its own clientId/redirect scope, so a code obtained for
        // one domain can't be redeemed against the other's client, and each
        // frontend's config.js can declare its own OIDC_CLIENT_ID.
        RegisteredClient spaClientShop = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("spa-client-shop")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(spaClientShopRedirectUri)
                .postLogoutRedirectUri(spaClientShopPostLogoutRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(StandardClaimNames.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();

        RegisteredClient spaClientChocolate = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("spa-client-chocolate")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(spaClientChocolateRedirectUri)
                .postLogoutRedirectUri(spaClientChocolatePostLogoutRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(StandardClaimNames.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();

        RegisteredClient externalClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("external-service")
                .clientSecret(externalClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(OidcScopes.OPENID)
                .build();

        // Used by profile to mint a service-to-service token for calling
        // storage's presign/delete endpoints under the user-files scope.
        RegisteredClient internalClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("internal-service")
                .clientSecret(internalClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("internal")
                .build();

        // Deliberately NOT the internal-service client above. "internal" is a
        // shared identity — profile uses it for storage, and anything else that
        // needs service-to-service access can too. Letting it also mean "delete
        // this user" would put the identity store in the blast radius of any
        // leak of those credentials. Only profile holds these.
        // See docs/users/blocking-users.md §3.1.
        RegisteredClient identityAdminClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("identity-admin")
                .clientSecret(identityAdminClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("identity.admin")
                .build();

        return new InMemoryRegisteredClientRepository(
                oidcClient, spaClientShop, spaClientChocolate, externalClient, internalClient,
                identityAdminClient);
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        ClientRegistration googleRegistration = ClientRegistration.withRegistrationId("google")
                .clientId(googleClientId)
                .clientSecret(googleClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(googleRedirectUri)
                .scope(OidcScopes.OPENID, OidcScopes.PROFILE, StandardClaimNames.EMAIL)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName(StandardClaimNames.SUB)
                .clientName("Google")
                .build();

        // auth-server is no longer an OAuth2 client of anything: it used to act as
        // the internal-service client to POST profile's notify endpoints, which is
        // now a Kafka event. The internal-service *RegisteredClient* above stays —
        // profile still uses those credentials to call storage.
        return new InMemoryClientRegistrationRepository(googleRegistration);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return (context) -> {
            if (context.getPrincipal() != null) {
                Set<String> roles = context.getPrincipal().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(java.util.stream.Collectors.toSet());
                context.getClaims().claim("roles", roles);

                // Google's `picture`, forwarded rather than stored. We already
                // request the `profile` scope from Google, so this arrives on
                // every federated login and was previously discarded. profile
                // caches it off this claim (docs/users/user-pic.md D1) instead
                // of authdb growing a column and identity.events growing an
                // event to keep that column fresh. Absent for form logins,
                // whose principal is a plain UserDetails — as it should be.
                if (context.getPrincipal().getPrincipal() instanceof OidcUser oidcUser
                        && oidcUser.getPicture() != null && !oidcUser.getPicture().isBlank()) {
                    context.getClaims().claim("picture", oidcUser.getPicture());
                }
            }
        };
    }

    /*

    @Bean
public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
    return context -> {
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            Authentication principal = context.getPrincipal();
            Set<String> roles = principal.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            context.getClaims().claim("roles", roles);
        }
    };
}
     */

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        AuthorizationServerSettings.Builder settings = AuthorizationServerSettings.builder();
        if (issuer != null && !issuer.isBlank()) {
            settings.issuer(issuer);
        }
        // else: leave issuer unset so Spring Authorization Server derives it
        // per-request (scheme+host+context-path, honoring X-Forwarded-* since
        // server.forward-headers-strategy=framework is set) — required to serve
        // two public domains from one instance.
        return settings.build();
    }
}
