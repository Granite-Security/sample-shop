# One backend, two websites: how we run granite-security.org and sichocolate.com side by side

**Status: shipped and live.** Both domains work today, sharing the exact same backend.

## Why bother? (the business case)

Say you have one working online shop: login, catalog, payment, delivery — all built,
tested, and running. Now someone says "let's also sell under a different brand name,
with a different look, for a different audience." The lazy-sounding but actually
*correct* answer is: **don't build a second shop. Build a second front door to the
same shop.**

That's the trade we made here. `granite-security.org` and `sichocolate.com` are two
different websites, with two different visual designs (`ui-shop` and `ui-demo`), aimed
at two different audiences — but underneath, they both call the exact same login
system, the same product catalog, the same payment processor, the same delivery
tracking. One set of servers. One database per service. One thing to patch when a
security fix comes out, one thing to scale when traffic grows, one team that needs to
understand how it works.

Why this matters for the business, not just the tech:

- **More storefronts = more chances to make a sale**, without more backend systems to
  build or run. A new brand, a partner-labeled version of the product, a regional
  variant — each one is a new coat of paint, not a new company to build from scratch.
- **The marginal cost of "one more UI" is small.** The expensive part of an e-commerce
  system is never the buttons and colors — it's login security, payment correctness,
  inventory consistency, order tracking. We already paid that cost once. Adding
  `sichocolate.com` cost us a new frontend deployment and a few config changes. It did
  **not** cost us a new auth system, a new payment integration, or a new database to
  keep in sync.
- **One backend to maintain instead of many.** Every bug fix, every security patch,
  every performance improvement in `auth-server`, `payment`, `shop`, `profile`, or
  `delivery` now benefits *every* storefront built on top of it, automatically, on the
  next deploy. There's no "did we remember to also fix it in the other copy?" problem,
  because there is no other copy.

The rest of this post is the "how." It turned out to be three separate problems, not
one, and the tricky part wasn't picking a solution — it was noticing all three problems
existed in the first place.

## The old rule: one domain at a time

Before this change, the system could only ever answer to *one* public web address.
Everything — the login server, the shopping API, the delivery tracker — was configured
with that one address baked in. If you wanted `sichocolate.com` to work, you had to
stop `granite-security.org` from working first, because several parts of the system
were quietly built on the assumption "there is exactly one website."

Three places had that assumption baked in:

1. The login server (`auth-server`) stamped every login token with a fixed
   web address, e.g. `https://granite-security.org/auth`. Only one address could ever be
   "correct" at a time.
2. Every backend service that checks login tokens (`shop`, `payment`, `profile`,
   `greetings`, `delivery`) was told "only trust tokens stamped with *this one* address."
3. The Kubernetes traffic router (the "Gateway") only knew how to point one web address
   at one frontend.

## Problem 1: the login token needs to say the right "who signed you in here"

Every time someone logs in, the login server hands the browser back a signed token
(a JWT) that says, among other things, *"I am `auth-server`, reachable at
`https://granite-security.org/auth`, and I vouch for this user."* That web address
inside the token is called the **issuer**. It's not decoration — every other service
checks it before trusting the token.

The old code hard-coded one issuer:

```java
// before
return AuthorizationServerSettings.builder().issuer(issuer).build();
```

The fix: don't hard-code it. Spring's login-server framework will happily figure out
"which domain is this request actually for?" on its own, per request, **if you just
don't tell it a fixed answer**:

```java
// after
AuthorizationServerSettings.Builder settings = AuthorizationServerSettings.builder();
if (issuer != null && !issuer.isBlank()) {
    settings.issuer(issuer);          // used only for local dev, one domain
}
// else: left unset on purpose in production — the framework derives the
// issuer from the incoming request (scheme + host), which is exactly
// "which of our two domains was this login attempt for?"
return settings.build();
```

This only works if the *real* website address survives the trip from the browser,
through our reverse proxy (nginx), through our internal gateway, to the login server.
That's carried in a header called `X-Forwarded-Host` — every hop has to pass it along
untouched, or the login server ends up guessing wrong. (More on how we found out the
hard way, below.)

## Problem 2: each storefront needs its own "ID card" with the login server

Logging in via OAuth/OIDC isn't just "type your password." The *frontend app itself*
also has to identify itself to the login server — with its own ID (`client_id`) and its
own registered "send the user back here after login" address. Before this change,
there was exactly one such ID, shared by whichever storefront happened to be live.

We gave each storefront its own ID instead of sharing one:

```java
RegisteredClient spaClientShop = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("spa-client-shop")
        .redirectUri(spaClientShopRedirectUri)             // https://granite-security.org/callback
        .postLogoutRedirectUri(spaClientShopPostLogoutUri) // https://granite-security.org
        .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(StandardClaimNames.EMAIL)
        .clientSettings(ClientSettings.builder()
                .requireAuthorizationConsent(true)
                .requireProofKey(true)
                .build())
        .build();

RegisteredClient spaClientChocolate = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("spa-client-chocolate")
        .redirectUri(spaClientChocolateRedirectUri)             // https://sichocolate.com/callback
        .postLogoutRedirectUri(spaClientChocolatePostLogoutUri) // https://sichocolate.com
        .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(StandardClaimNames.EMAIL)
        .clientSettings(ClientSettings.builder()
                .requireAuthorizationConsent(true)
                .requireProofKey(true)
                .build())
        .build();
```

Why bother giving them separate IDs instead of just registering two addresses under
one shared ID? Security hygiene: if one storefront's login flow were ever compromised,
the attacker only gets that storefront's ID card, not a master key that also works on
the other one. It also means each frontend's configuration is honest about what it is
— `sichocolate.com` literally says "I am `spa-client-chocolate`," not "I am some shared
thing that also happens to be someone else."

## Problem 3: "only trust tokens from address X" needs to become "trust these two addresses"

Every backend service that checks login tokens (`shop`, `payment`, `profile`,
`greetings`, `delivery`) used to have one hard-coded trusted issuer address. Once the
login server could legitimately stamp tokens with *either* of two addresses, that
single-address check had to become an allow-list:

```java
@Bean
public ReactiveJwtDecoder jwtDecoder() {
    // The signature-checking key is the same no matter which domain issued
    // the token — it's the same login server, same key, so we point at a
    // fixed internal address for that part, not something domain-specific.
    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();

    // But we only trust tokens that say they came from one of OUR domains.
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
```

The important insight here: **checking the signature and checking the issuer are two
separate steps.** The signature just proves "our login server really did sign this" —
it has nothing to do with which domain it was signed for. So one signing key can safely
back tokens for both domains; we just widened the second check (the issuer allow-list)
from "must be exactly this one" to "must be one of these two."

## Problem 4: routing two domains to two different websites

The last piece is plain traffic routing: when someone visits `granite-security.org`,
Kubernetes needs to serve `ui-shop`; when someone visits `sichocolate.com`, it needs to
serve `ui-demo`. Our traffic router (a Kubernetes "Gateway") can hold more than one
domain at once, each pointed at a different backend:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: granite-gateway
spec:
  gatewayClassName: traefik
  listeners:
    - name: https-shop
      hostname: granite-security.org
      port: 443
      tls: { certificateRefs: [{ name: granite-security.org-tls }] }
    - name: https-chocolate
      hostname: sichocolate.com
      port: 443
      tls: { certificateRefs: [{ name: sichocolate.com-tls }] }
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: granite-route-shop }
spec:
  parentRefs: [{ name: granite-gateway, sectionName: https-shop }]
  hostnames: [granite-security.org]
  rules: [{ backendRefs: [{ name: ui-shop, port: 80 }] }]
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: granite-route-chocolate }
spec:
  parentRefs: [{ name: granite-gateway, sectionName: https-chocolate }]
  hostnames: [sichocolate.com]
  rules: [{ backendRefs: [{ name: ui-demo, port: 80 }] }]
```

Kubernetes even hands out a separate HTTPS certificate for each domain automatically —
no manual certificate work needed for the second domain, it just asks for one the same
way it did for the first.

## What actually went wrong while shipping this (and why it's worth reading)

Nothing above is exotic — this pattern (one backend, many "skins") is common. But
getting it live surfaced three real lessons worth writing down, because each one
looked like a different bug before we traced it back:

1. **A setting we added to "help" actually broke things.** We turned on a Spring
   setting (`forward-headers-strategy`) on the internal gateway, thinking it was needed
   to correctly read the real domain name. It turned out to do the opposite: it read
   the real domain name for its *own* use, then **stripped that information before
   passing the request onward** — so the login server, one hop later, never saw it and
   fell back to an internal address instead of the real public one. The fix was to
   *remove* the setting, not add more of it. Lesson: a setting that "sounds right" can
   still be wrong for a pure pass-through service — verify what it actually does to the
   request, don't just trust the name.

2. **An old default value was hiding a bug for months.** One frontend had a
   configuration template file where the actual code line had accidentally been left
   commented out — so the frontend was always silently falling back to a hard-coded
   default ID. This never caused a problem before, because that hard-coded default
   happened to match the one shared login ID that used to exist. The moment we split
   into two separate login IDs, the coincidence ended, and the bug became visible.
   Lesson: a default that happens to match reality isn't the same as a default that's
   actually correct — splitting one shared thing into two is a great way to expose
   exactly this kind of previously-invisible mistake.

3. **We nearly overwrote real production passwords with placeholder text**, by
   temporarily using an example/template config file (full of `CHANGE_ME` values) to
   test that our configuration was shaped correctly, and forgetting to swap the real
   file back in before applying it for real. Kubernetes doesn't restart your database
   when you touch a *secret*, so the real password kept working in the database
   itself — but every app trying to log in with it was now using the wrong value.
   Lesson: template/example files and real secret files should never have swappable
   names in the moment you're about to run something for real — the five seconds it
   takes to double-check which one you're pointing at is much cheaper than the
   incident.

## What this buys us going forward

Adding a **third** storefront tomorrow is now a small, mechanical job:

- One more login "ID card" in the login server (a few lines, like `spa-client-shop`
  above).
- One more entry in the trusted-issuer list on each backend service (one line each).
- One more domain + certificate + route in the Gateway (copy-paste the block above).
- One new frontend to design and deploy.

Nothing about the shopping cart, the payment processor, the delivery tracker, or the
database changes. That's the whole point: the expensive, security-critical, hard-to-
get-right part of the system was built once, and now it's reusable.
