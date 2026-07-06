# How to run granite-security in local kind

This is the "start from nothing, get everything running" guide — follow it top to bottom on a
fresh machine, or skip to whichever step you need if the cluster already partially exists.

Rationale/history for *why* things are set up this way (issuer matching, the SPA secure-context
bug, the Kafka `KAFKA_PORT` collision, ...) lives in `k8s/troubleshooting.md` — this file is just
the "what to run" checklist.

---

## 0. One-time prerequisites (per machine)

- Docker Desktop running.
- `kind` (`brew install kind`), `kubectl` (`brew install kubectl`).
- Java 25 + each service's own Gradle wrapper (no separate install needed, `./gradlew` bootstraps).
- Node 22+ for `ui-shop`.
- One `/etc/hosts` entry (needed so the browser can resolve the same hostname the pods use
  internally via K8s DNS):
  ```bash
  echo '127.0.0.1 gateway' | sudo tee -a /etc/hosts
  ```
  Check it's not already there first (`grep gateway /etc/hosts`) to avoid a duplicate line.

---

## 1. App secrets (new machine only — this file is git-ignored, never comes from `git clone`)

```bash
cp k8s/base/secrets.yaml.example k8s/base/secrets.yaml
```

Edit `k8s/base/secrets.yaml` and fill in real values for whichever of these you actually want
working:
- `stripe-secret-key` / `stripe-publishable-key` / `stripe-webhook-secret` — only needed for
  checkout/payment. Leave blank to skip; catalog/orders/profile/delivery all work without Stripe.
- `google-client-id` / `google-client-secret` — only needed for "Login with Google". Leave blank
  to skip; form login (`user`/`user`, `admin`/`admin`, `manager`/`manager`) always works.

---

## 2. TLS certificate for `gateway` (new machine, or cert missing/expired)

The browser-facing front door (`ui-shop`'s nginx) terminates TLS on `gateway:8443` — this is
required for the SPA's PKCE login to work at all (`crypto.subtle` / Web Crypto API only exists in
a browser "secure context": `https://`, or the literal hostnames `localhost`/`127.0.0.1`. Plain
`http://gateway:8080` doesn't qualify — see `k8s/troubleshooting.md` for the full story).

Skip this step if `k8s/certs/gateway-cert.pem` and `gateway-key.pem` already exist and haven't
expired (825-day validity from whenever they were generated).

```bash
mkdir -p k8s/certs
cd k8s/certs
openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout gateway-key.pem -out gateway-cert.pem \
  -subj "/CN=gateway" \
  -addext "subjectAltName=DNS:gateway"
cd -
```

(There's also an `mkcert`-based option that avoids the browser's self-signed-cert warning
entirely — see `k8s/troubleshooting.md` §"Option A" if you'd rather set that up instead.)

---

## 3. Create the kind cluster

```bash
kind create cluster --config k8s/kind/kind-config.yaml --name granite
```

If a `granite` cluster already exists and you just need to change something in
`kind-config.yaml` (port mappings only take effect at cluster **creation**), delete it first:
```bash
kind delete cluster --name granite
```

---

## 4. Build the service images

```bash
for s in auth-server gateway greetings shop payment profile delivery; do
  (cd $s && ./gradlew build -x test)
  docker build -t granite-$s:latest $s/
done
docker build -t granite-ui-shop:latest ui-shop/
```

---

## 5. Load images into kind

```bash
kind load docker-image --name granite \
  granite-auth-server:latest granite-gateway:latest granite-greetings:latest \
  granite-shop:latest granite-payment:latest granite-profile:latest \
  granite-delivery:latest granite-ui-shop:latest
```

---

## 6. Apply the manifests

```bash
kubectl apply -k k8s/kind
```

This creates the `granite` namespace, the `granite-config` ConfigMap, the `granite-secrets`
Secret (from step 1), and every Deployment/Service/PVC.

---

## 7. Create the TLS secret

This one is **not** part of the kustomization (it's created ad-hoc, not tracked as a resource), so
it does not come back automatically from step 6 — you must (re)create it by hand every time the
`granite` namespace is recreated from scratch:

```bash
kubectl -n granite create secret tls granite-tls-cert \
  --cert=k8s/certs/gateway-cert.pem \
  --key=k8s/certs/gateway-key.pem
```

If it already exists and you regenerated the cert, delete first:
```bash
kubectl -n granite delete secret granite-tls-cert
```

---

## 8. Wait for everything to come up

```bash
kubectl -n granite get pods -w
```

Expect all of these to reach `1/1 Running`: `auth-server`, `gateway`, `greetings`, `shop`,
`payment`, `profile`, `delivery`, `ui-shop`, `kafka`, and the 5 `postgres-*` pods. `auth-server`
may restart a couple of times early on while its Postgres finishes starting — that's normal, not
a real failure, as long as it settles into `Running` within a minute or so.

`greetings`/`shop`/`payment`/`profile`/`delivery` each have a `wait-for-issuer` initContainer that
blocks (`Init:0/1`) until `auth-server` + `gateway` are reachable — that's expected too, not stuck.

---

## 9. Access it

Open in a browser:

```
https://gateway:8443/
```

The first time, you'll get a certificate warning (self-signed cert) — click through it (Chrome:
Advanced → Proceed; Firefox: Advanced → Accept the Risk and Continue). This is a one-time-per-
browser-profile step, not a functional issue.

**Verify:**
- The storefront loads and shows the product catalog.
- Click **Login** → should land on the auth-server's real login form (not get stuck on `/login`)
  → sign in as `user` / `user` (or `admin`/`admin`, `manager`/`manager`) → redirected back to the
  SPA, now showing as logged in, no console errors.
- Browse the catalog, place a test order, check it shows up under Orders.
- If you filled in Stripe keys in step 1: run through checkout.

**Quick non-browser sanity checks**, useful if something looks broken and you want to narrow down
whether it's the browser/SPA or the backend:
```bash
curl -sk https://gateway:8443/api/shop/products | head -c 200; echo
curl -sk https://gateway:8443/auth/.well-known/openid-configuration | grep issuer
```
The issuer should read `"issuer":"http://gateway:8080/auth"` — that's correct and expected (see
`k8s/troubleshooting.md` for why it's `http://` here even though the browser reaches everything
over `https://`).

---

## 10. Everyday commands

**Rebuild one service after a code change:**
```bash
(cd shop && ./gradlew build -x test)
docker build -t granite-shop:latest shop/
kind load docker-image --name granite granite-shop:latest
kubectl -n granite delete pod -l app=shop
```

**Full teardown:**
```bash
kind delete cluster --name granite
```
(Remember: next time you create the cluster, you'll need to redo steps 3, 5, 6, and 7 — the TLS
secret and all loaded images are gone with the cluster.)

**Going back to running everything from IntelliJ on localhost:** just stop the kind cluster
(`kind delete cluster --name granite`, or leave it running — it doesn't matter, since it only
occupies host ports `8080`/`8443` while it's up) and run the services from IntelliJ as usual, no
env vars needed. This is an exclusive-or setup: kind and IntelliJ-local both bind the same host
ports, so don't run both against port `8080` at the same time.

---

## If something's wrong

Check `k8s/troubleshooting.md` first — it has the full investigation + fix for every issue hit so
far (Kafka `CrashLoopBackOff` from a `KAFKA_PORT` env var collision, the SPA login/secure-context
bug, the issuer-matching fix). If it's a new problem, that file is also where to add the next
entry once it's diagnosed.
