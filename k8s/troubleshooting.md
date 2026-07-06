# Troubleshooting log

Running record of problems hit while getting the stack running in kind, what was investigated,
and what fixed it. Newest entries at the top.

---

## 2026-07-06 — SPA login stuck on `/login`, never redirects to auth-server (FIXED — Option B applied)

**Confirmed** via browser DevTools console, exactly as predicted:
```
Uncaught (in promise) Error: Crypto.subtle is available only in secure contexts (HTTPS).
    at e.generateCodeChallenge (index-DOR4-V3f.js:11:30665)
    at e.create (index-DOR4-V3f.js:11:58237)
    at e.create (index-DOR4-V3f.js:11:59765)
    at Kr.createSigninRequest (index-DOR4-V3f.js:11:64394)
    at async pi._signinStart (index-DOR4-V3f.js:11:89863)
    at async pi.signinRedirect (index-DOR4-V3f.js:11:84879)
```

**Symptom:** `curl http://localhost:8080/api/shop/products` works (public catalog through
gateway → shop confirmed working). But in the browser: loaded `http://gateway:8080/` fine,
clicked "Login", page navigated to `http://gateway:8080/login` and stayed there showing
"Redirecting to login..." — never advanced to the auth-server's actual login page.

**Investigation:**
1. Traced the click: `Header.tsx` links to the SPA's own client-side route `/login`
   (React Router, not a real navigation) → renders `ui-shop/src/pages/Login.tsx` → its
   `useEffect` calls `userManager.signinRedirect()` with no `.catch()`. That call is supposed to
   itself trigger a *real* browser navigation to `{authority}/oauth2/authorize?...` on the
   auth-server. Staying on `/login` indefinitely means `signinRedirect()` is failing before it
   ever navigates — not a wrong-URL issue (the initial `/login` in the address bar is expected
   and momentary by design; the auth-server's own login form only appears after a successful
   redirect to `/auth/oauth2/authorize`, as `/auth/login`).
2. `spa-client` is registered with `ClientSettings.builder().requireProofKey(true)` in
   `auth-server/.../SecurityConfig.java` — PKCE is mandatory, and `oidc-client-ts` doesn't disable
   PKCE by default, so `signinRedirect()` must generate a PKCE code challenge via the Web Crypto
   API before it can build the redirect URL.
3. Found the exact failure point in `oidc-client-ts`'s bundled source
   (`node_modules/oidc-client-ts/dist/esm/oidc-client-ts.js`):
   ```js
   static async generateCodeChallenge(code_verifier) {
     if (!crypto.subtle) {
       throw new Error("Crypto.subtle is available only in secure contexts (HTTPS).");
     }
     ...
   }
   ```
   `crypto.subtle` (Web Crypto API) is only exposed by browsers in a "secure context": pages
   served over `https://`, or served from the literal hostnames `localhost` / `127.0.0.1` /
   `[::1]`. `http://gateway:8080` is neither — `gateway` is a custom hostname mapped via
   `/etc/hosts`, not literally `localhost`, and the scheme is plain `http`. So `crypto.subtle` is
   `undefined`, `generateCodeChallenge` throws, and since `signinRedirect()` is called without a
   `.catch()` in `Login.tsx`, it surfaces only as an unhandled promise rejection in the console —
   the page never navigates anywhere.

**Root cause (pending final confirmation from browser DevTools console):** the same design
choice that makes `gateway` work as a single dual-resolvable hostname (pods via K8s DNS, browser
via `/etc/hosts`) is exactly what disqualifies it from being a secure context for the browser —
a hostname trustworthy enough to satisfy the browser's secure-context check (`localhost` /
`127.0.0.1`) is, by the same definition, one that means "myself" from inside a pod, so it could
never resolve to the real gateway Service. Plain HTTP on a custom hostname cannot satisfy both
sides at once.

**Fix — applied (Option B, plain `openssl` self-signed cert):**
terminate TLS at the browser-facing front door only (`ui-shop`'s nginx). `https://` origins are
secure contexts regardless of hostname; the internal hops (nginx → gateway, resource servers →
auth-server for JWKS discovery) stay plain HTTP, since that's invisible to the browser's
mixed-content/secure-context rules — only the browser's own address bar needs `https://`. The
issuer string itself (`AUTH_SERVER_ISSUER` / `OIDC_AUTHORITY`, `http://gateway:8080/auth`) does
**not** need to change, since the earlier issuer-matching fix already decoupled "what the JWT's
`iss` says" from "what origin the SPA's endpoint URLs are built from" — only the latter would move
to `https://gateway:<port>`.

Needs: a cert for `gateway`, a TLS listener added to `ui-shop/nginx.conf`, a new kind port
mapping, and updated `SPA_CLIENT_REDIRECT_URI` / `SPA_CLIENT_POST_LOGOUT_REDIRECT_URI` to the new
`https://` origin. Full step-by-step instructions for both cert options below (kept for
reference/reuse — e.g. if the cert expires or the cluster gets rebuilt from scratch).

### Fix instructions — pick Option A or B, then do the common steps

Both options end up with the same two files: `k8s/certs/gateway-cert.pem` and
`k8s/certs/gateway-key.pem`. Everything after cert generation is identical either way.

#### Option A — `mkcert` (no browser warning; installs a local trusted CA — one-time, reversible)

```bash
brew install mkcert nss
mkcert -install                 # adds mkcert's local CA to your system + browser trust stores
mkdir -p k8s/certs
cd k8s/certs
mkcert -key-file gateway-key.pem -cert-file gateway-cert.pem gateway
cd -
```
To undo the trust-store change later: `mkcert -uninstall`.

#### Option B — plain `openssl` self-signed (no installs; one-time click-through browser warning)

```bash
mkdir -p k8s/certs
cd k8s/certs
openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout gateway-key.pem -out gateway-cert.pem \
  -subj "/CN=gateway" \
  -addext "subjectAltName=DNS:gateway"
cd -
```
The first time you visit `https://gateway:8443/` the browser will show "Your connection is not
private" — click through (Chrome: Advanced → Proceed; Firefox: Advanced → Accept the Risk). Once
accepted, the origin is still a full secure context (`https://` scheme is what matters for
`crypto.subtle`, not certificate trust) — this is a one-time UI warning, not a functional blocker.

#### Common steps (after `k8s/certs/gateway-{cert,key}.pem` exist)

1. **Gitignore the cert directory** (same reasoning as `k8s/base/secrets.yaml` — local-only,
   never committed). Add to `.gitignore`:
   ```
   k8s/certs
   ```

2. **Create the TLS Secret** in the `granite` namespace:
   ```bash
   kubectl -n granite create secret tls granite-tls-cert \
     --cert=k8s/certs/gateway-cert.pem \
     --key=k8s/certs/gateway-key.pem
   ```
   (Re-run with `kubectl -n granite delete secret granite-tls-cert` first if it already exists and
   you regenerated the cert.)

3. **Mount the secret into `ui-shop` and add a second container port** — edit
   `k8s/base/ui-shop.yaml`:
   ```yaml
   ---
   apiVersion: apps/v1
   kind: Deployment
   metadata:
     name: ui-shop
   spec:
     replicas: 1
     selector:
       matchLabels:
         app: ui-shop
     template:
       metadata:
         labels:
           app: ui-shop
       spec:
         containers:
           - name: ui-shop
             image: granite-ui-shop:latest
             imagePullPolicy: Never
             ports:
               - containerPort: 80
               - containerPort: 443
             env:
               - name: OIDC_AUTHORITY
                 valueFrom:
                   configMapKeyRef:
                     name: granite-config
                     key: OIDC_AUTHORITY
               - name: OIDC_CLIENT_ID
                 valueFrom:
                   configMapKeyRef:
                     name: granite-config
                     key: OIDC_CLIENT_ID
               - name: STRIPE_PUBLISHABLE_KEY
                 valueFrom:
                   secretKeyRef:
                     name: granite-secrets
                     key: stripe-publishable-key
             volumeMounts:
               - name: tls-cert
                 mountPath: /etc/nginx/certs
                 readOnly: true
         volumes:
           - name: tls-cert
             secret:
               secretName: granite-tls-cert
   ---
   apiVersion: v1
   kind: Service
   metadata:
     name: ui-shop
   spec:
     ports:
       - port: 80
         name: http
       - port: 443
         name: https
     selector:
       app: ui-shop
   ```
   (Only the `ports` list, the new `volumeMounts`/`volumes` block, and the `name:` labels on the
   Service ports are new — everything else is unchanged from today.)

4. **Add the HTTPS server block to `ui-shop/nginx.conf`** — append this second `server` block
   after the existing `listen 80` one (leave the existing block as-is):
   ```nginx
   server {
       listen       443 ssl;
       server_name  gateway;

       ssl_certificate     /etc/nginx/certs/tls.crt;
       ssl_certificate_key /etc/nginx/certs/tls.key;

       root   /usr/share/nginx/html;
       index  index.html;

       location /api/ {
           proxy_pass http://gateway:8080;
           proxy_set_header Host localhost:8080;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-Host $http_host;
           proxy_set_header X-Forwarded-Proto $scheme;
           proxy_redirect off;
       }

       location /auth/ {
           proxy_pass http://gateway:8080;
           proxy_set_header Host localhost:8080;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-Host $http_host;
           proxy_set_header X-Forwarded-Proto $scheme;
           proxy_redirect off;
       }

       location /oauth2/ {
           proxy_pass http://gateway:8080;
           proxy_set_header Host localhost:8080;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-Host $http_host;
           proxy_set_header X-Forwarded-Proto $scheme;
           proxy_redirect off;
       }

       location / {
           try_files $uri $uri/ /index.html;
       }
   }
   ```
   Note: the k8s TLS Secret's default keys are literally `tls.crt` / `tls.key` regardless of the
   input filenames used in step 2 — that's why nginx references `/etc/nginx/certs/tls.crt`, not
   `gateway-cert.pem`.

5. **Expose the new port from kind** — edit `k8s/kind/kind-config.yaml`, add a second
   `extraPortMappings` entry (keep the existing `30080`/`8080` one):
   ```yaml
   kind: Cluster
   apiVersion: kind.x-k8s.io/v1alpha4
   nodes:
     - role: control-plane
       extraPortMappings:
         - containerPort: 30080
           hostPort: 8080
           protocol: TCP
         - containerPort: 30443
           hostPort: 8443
           protocol: TCP
   ```
   This only takes effect on cluster **creation** — if the `granite` cluster already exists,
   you'll need `kind delete cluster --name granite` and recreate it for this mapping to appear.

6. **Add the NodePort for 443** — edit `k8s/kind/ui-shop-patch.yaml`:
   ```yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: ui-shop
   spec:
     type: NodePort
     ports:
       - port: 80
         targetPort: 80
         nodePort: 30080
         name: http
       - port: 443
         targetPort: 443
         nodePort: 30443
         name: https
   ```

7. **Move the SPA's redirect URIs to the new HTTPS origin** — edit
   `k8s/kind/config-patch.yaml`, change just these two lines (everything else, including
   `AUTH_SERVER_ISSUER`/`OIDC_AUTHORITY` which stay `http://gateway:8080/auth` on purpose, is
   unchanged):
   ```yaml
     SPA_CLIENT_REDIRECT_URI: "https://gateway:8443/callback"
     SPA_CLIENT_POST_LOGOUT_REDIRECT_URI: "https://gateway:8443/"
   ```
   (`OIDC_CLIENT_REDIRECT_URI`/`OIDC_CLIENT_POST_LOGOUT_REDIRECT_URI`, used by the separate
   confidential `oidc-client`/gateway-OAuth2-client flow, stay on `http://gateway:8080/...` —
   that flow doesn't touch the browser's Web Crypto API and isn't affected by any of this.)

8. **Rebuild the `ui-shop` image** (nginx.conf is baked in at build time, not env-injected) **and
   redeploy**:
   ```bash
   docker build -t granite-ui-shop:latest ui-shop/
   kind load docker-image --name granite granite-ui-shop:latest
   kubectl apply -k k8s/kind
   kubectl -n granite delete pod -l app=ui-shop
   ```

9. **Verify:**
   - `echo '127.0.0.1 gateway' | sudo tee -a /etc/hosts` (should already be present from earlier).
   - Open `https://gateway:8443/` in the browser (accept the cert warning if using Option B).
   - Click Login → should now advance past `/login` to the auth-server's real login page at
     `https://gateway:8443/auth/oauth2/authorize...` → `.../auth/login` → sign in as `user`/`user`
     → redirected back to the SPA authenticated, no console errors.

**What was actually done (2026-07-06, Option B):**
- Generated `k8s/certs/gateway-{cert,key}.pem` with `openssl req -x509 ... -subj "/CN=gateway"
  -addext "subjectAltName=DNS:gateway"`; added `k8s/certs` to `.gitignore`.
- Created the `granite-tls-cert` TLS secret in the `granite` namespace from those files (this is
  an ad-hoc `kubectl create secret`, not part of the kustomization, so it does **not** come back
  automatically on `kubectl apply -k k8s/kind` — must be recreated by hand after any
  `kind delete cluster`/`kubectl delete namespace granite`).
- Applied all manifest edits from the instructions above: `k8s/base/ui-shop.yaml` (443 container
  port + volume mount), `ui-shop/nginx.conf` (new `listen 443 ssl` server block),
  `k8s/kind/kind-config.yaml` (host `8443` → NodePort `30443`; also dropped the old dead
  `9090→30090` mapping while in this file — see the gaps noted in `k8s/todo.md` §4),
  `k8s/kind/ui-shop-patch.yaml` (NodePort 443 added), `k8s/kind/config-patch.yaml`
  (`SPA_CLIENT_REDIRECT_URI`/`SPA_CLIENT_POST_LOGOUT_REDIRECT_URI` → `https://gateway:8443/...`).
- Rebuilt `granite-ui-shop:latest`; sanity-checked the new nginx config in isolation
  (`docker run` with the cert files bind-mounted, `nginx -t` passed, `curl -sk https://localhost/`
  → 200) before touching the cluster.
- The `kind-config.yaml` port mapping only takes effect at cluster **creation**, so the whole
  cluster was recreated: `kind delete cluster --name granite` →
  `kind create cluster --config k8s/kind/kind-config.yaml --name granite` → reloaded all 8
  service images → `kubectl apply -k k8s/kind` → recreated `granite-tls-cert` → all 14 pods
  reached `1/1 Running` (auth-server had 3 early restarts waiting on its Postgres to accept
  connections, then stabilized — not related to this fix).
- **Verified end-to-end, via curl (not yet clicked through in an actual browser):**
  - `curl -sk https://gateway:8443/` → 200 (SPA served over TLS).
  - `curl -sk https://gateway:8443/api/shop/products` → 200, catalog JSON (proxy path works
    over the new HTTPS listener too, not just plain HTTP).
  - `curl -sk https://gateway:8443/auth/.well-known/openid-configuration` → issuer still reads
    `http://gateway:8080/auth`, confirming the issuer string is correctly unaffected by the new
    TLS front door (as designed — only the SPA's own origin needed to change).
  - Simulated the actual PKCE authorize request (`response_type=code&client_id=spa-client
    &redirect_uri=https://gateway:8443/callback&code_challenge=...&code_challenge_method=S256`)
    against `https://gateway:8443/auth/oauth2/authorize` → got a clean `302` to
    `http://gateway:8080/auth/login`, no `invalid redirect_uri` / `invalid_client` error — confirms
    the registered `spa-client` redirect URI now matches what the browser will actually send.
  - **Still to do:** click through the real flow in an actual browser (this only proves the
    server-side wiring is correct; the original bug was purely client-side — `crypto.subtle` only
    exists in a real browser secure context, curl can't exercise that part at all).

---

## 2026-07-06 — `kafka` pod CrashLoopBackOff / `Error` (exit code 1)

**Symptom:**
```
kafka-766d846897-j95jk   0/1   Error   4 (2m ago)   3m21s
```
`kubectl -n granite logs -l app=kafka` always stopped at the same point, no further output:
```
===> User
uid=1000(appuser) gid=1000(appuser) groups=1000(appuser)
===> Configuring ...
port is deprecated. Please use KAFKA_ADVERTISED_LISTENERS instead.
```

**Investigation:**
1. `kubectl -n granite describe pod -l app=kafka` — confirmed `exitCode: 1`, `reason: Error`,
   restarting continuously (kubelet backing off). No OOMKill, no failed image pull, no
   node-level issue — the container itself exits cleanly with status 1.
2. `kubectl -n granite logs <pod> --previous` didn't work ("unable to retrieve container logs"),
   and streaming with `-f` across a restart only ever showed the same 3 lines above — so the
   crash happens very early, inside the image's `configure` step, before it even reaches its own
   "Running preflight checks" log line.
3. Ruled out the obvious volume/permissions angle: spun up a debug pod
   (`confluentinc/cp-kafka:latest`, same `kafka-pvc` mounted) and confirmed
   `/var/lib/kafka/data` is `drwxrwxrwx`, writable by the container's non-root `appuser` (uid
   1000), and completely empty — meaning Kafka never got far enough to write anything, on any of
   its restart attempts. Not a storage-class/local-path-provisioner permissions problem.
4. Ran the exact same image with the exact same env vars via plain `docker run` (no k8s) —
   **it booted fine**, all the way to a running broker. So something about the *k8s pod
   environment specifically* differs from a plain container run with identical explicit env vars.
5. Extracted `/etc/confluent/docker/configure` from the image and grepped for the deprecation
   message:
   ```bash
   if [[ -n "${KAFKA_PORT-}" ]]
   then
     echo "port is deprecated. Please use KAFKA_ADVERTISED_LISTENERS instead."
     exit 1
   fi
   ```
   The image **unconditionally exits 1** if a `KAFKA_PORT` env var is present at all, regardless
   of its value — it's a rejected legacy setting, not just a warning.
6. Re-ran `configure` manually inside a debug pod with `bash -x` to see every env var in scope.
   Confirmed the culprit:
   ```
   + [[ -n tcp://10.96.77.83:9092 ]]
   + echo 'port is deprecated. Please use KAFKA_ADVERTISED_LISTENERS instead.'
   + exit 1
   ```
   `KAFKA_PORT=tcp://10.96.77.83:9092` was present in the pod's environment — **but nobody set
   it**. It's a Kubernetes auto-injected variable: for every Service in a namespace, Kubernetes
   injects Docker-links-style env vars into every pod in that namespace
   (`<SERVICE_NAME>_PORT`, `<SERVICE_NAME>_SERVICE_HOST`, etc. — legacy
   `spec.enableServiceLinks` behavior, defaults to `true`). Because the Kafka **Service** in this
   project is literally named `kafka`, Kubernetes injects `KAFKA_PORT`, and that name collides
   exactly with the Confluent image's own deprecated legacy setting of the same name.

**Root cause:** name collision between a Kubernetes-auto-injected env var
(`KAFKA_PORT`, from the `kafka` Service) and a deprecated Confluent Kafka Docker image
setting also called `KAFKA_PORT`, which the image's `configure` script rejects unconditionally.

**Fix:** disable Kubernetes' automatic service-link env var injection for the kafka Deployment's
pod spec — `k8s/base/kafka.yaml`:
```yaml
spec:
  template:
    spec:
      enableServiceLinks: false   # added
      containers:
        - name: kafka
          ...
```

**Verification:**
```bash
kubectl apply -k k8s/kind
kubectl -n granite delete pod -l app=kafka
kubectl -n granite get pods -l app=kafka   # → 1/1 Running, 0 restarts
kubectl -n granite logs -l app=kafka --tail=30
```
Broker started cleanly, `orders.events` topic auto-created, and the `delivery.order.consumer` /
`delivery.payment.consumer` consumer groups (from the `delivery` and `payment` services) joined
and stabilized immediately — confirming Kafka is now reachable by its dependent services, not
just that the pod is `Running`.

**Note for later:** this class of bug (a Service name colliding with an app's own env var
convention) could in principle recur for any other Service — worth keeping `enableServiceLinks:
false` in mind as a default for Deployments in this project if a similar unexplained crash shows
up again, rather than re-deriving this from scratch.
