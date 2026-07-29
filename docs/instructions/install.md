# Install: single-node Hetzner cluster + granite stack on `iaka.com`

Target: Hetzner VPS `88.77.66.55`, Debian 12, one node (control-plane + worker).
Domain: `iaka.com`, registered at Cloudflare.

Sources: `k8s/hetzner/install-k8s-on-hetzner.md`, `k8s/hetzner/cloudify.md`,
`docs/plans/storage.md` §8, `storage/local/garage-init.sh`.

**External accounts required** (details in step 13):

| Provider | For | Needed keys |
|---|---|---|
| Cloudflare | DNS for `iaka.com` | — |
| Google Cloud | "Sign in with Google" | client ID + secret |
| Stripe | Checkout | publishable + secret + webhook signing secret |
| Resend | Transactional email | API key + a verified sending domain |
| Docker Hub | Only if building your own images (step 14) | access token |

Google, Stripe and Resend are each optional — the stack runs without them, with that feature disabled.

---

## 1. Local prerequisites

```bash
brew install kubectl helm
python3 -m venv ~/kubespray-venv
```

## 2. SSH key + admin user

```bash
ssh-keygen -t ed25519 -C "iaka-k8s" -f ~/.ssh/iaka_k8s
ssh-copy-id -i ~/.ssh/iaka_k8s.pub root@88.77.66.55
```

On the server as root:

```bash
adduser k8sadmin
usermod -aG sudo k8sadmin
rsync --archive --chown=k8sadmin:k8sadmin ~/.ssh /home/k8sadmin
echo "k8sadmin ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/k8sadmin
```

## 3. Harden SSH (Optional)

On the server, set in `/etc/ssh/sshd_config`: `PermitRootLogin no`, `PasswordAuthentication no`.

```bash
sudo systemctl restart sshd
```

## 4. Firewall

On the server. The forward/INPUT rules are mandatory — without them CoreDNS and Calico crash-loop.

```bash
sudo apt update && sudo apt install -y ufw
sudo ufw allow OpenSSH
sudo ufw allow 6443/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow to 10.233.0.0/18 comment 'k8s service+pod CIDR'
sudo sed -i 's/DEFAULT_FORWARD_POLICY="DROP"/DEFAULT_FORWARD_POLICY="ACCEPT"/' /etc/default/ufw
sudo ufw enable
sudo ufw reload
```

## 5. Cloudflare DNS

All records **A → `88.77.66.55`, DNS-only (grey cloud)**. Proxied/orange breaks the ACME HTTP-01 solver.

```
iaka.com              # storefront
media.iaka.com        # garage s3_web, public product-media reads
s3.iaka.com           # garage S3 API, browser PUTs to presigned URLs
garage.iaka.com       # garage-webui admin
```

All four are required: cert-manager requests a certificate per listener hostname, and a record that doesn't resolve leaves that Certificate stuck in `Pending`.

```bash
dig +short iaka.com   # expect 88.77.66.55
```

## 6. Kubespray

```bash
git clone https://github.com/kubernetes-sigs/kubespray.git ~/code/kubespray
cd ~/code/kubespray && git checkout v2.31.0
source ~/kubespray-venv/bin/activate
pip install -U pip && pip install -r requirements.txt
cp -rfp inventory/sample inventory/iaka
```

`inventory/iaka/inventory.ini`:

```ini
[all]
node1 ansible_host=88.77.66.55 ip=88.77.66.55 ansible_user=k8sadmin ansible_ssh_private_key_file=~/.ssh/iaka_k8s

[kube_control_plane]
node1

[etcd]
node1

[kube_node]
node1

[calico_rr]

[k8s_cluster:children]
kube_control_plane
kube_node
calico_rr
```

In `inventory/iaka/group_vars/k8s_cluster/k8s-cluster.yml` set `kube_proxy_mode: ipvs` and untaint the control plane:

```yaml
kube_node_taints:
  - key: node-role.kubernetes.io/control-plane
    effect: NoSchedule
    state: absent
```

```bash
ansible -i inventory/iaka/inventory.ini -m ping all
ansible-playbook -i inventory/iaka/inventory.ini --become --become-user=root cluster.yml
```

## 7. Kubeconfig

```bash
ssh -i ~/.ssh/iaka_k8s k8sadmin@88.77.66.55 "sudo cat /etc/kubernetes/admin.conf" > ~/.kube/iaka.yaml
kubectl --kubeconfig ~/.kube/iaka.yaml config rename-context kubernetes-admin@cluster.local iaka-admin
```

Then edit `~/.kube/iaka.yaml` and rename the `cluster.local` cluster → `iaka` and the `kubernetes-admin` user → `iaka-admin`, updating the `contexts:` references. Kubespray gives every cluster identical default names; renaming only the context silently merges into an existing cluster's entry.

```bash
KUBECONFIG=~/.kube/config:~/.kube/iaka.yaml kubectl config view --flatten > /tmp/merged
mv /tmp/merged ~/.kube/config && chmod 600 ~/.kube/config
kubectl config use-context iaka-admin
kubectl get nodes -o wide
```

Confirm `kubectl config current-context` reads `iaka-admin` before every step below.

## 8. StorageClass

```bash
kubectl apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.36/deploy/local-path-storage.yaml
kubectl patch storageclass local-path -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
kubectl get storageclass
```

## 9. Traefik + Gateway API

```bash
helm repo add traefik https://traefik.github.io/charts && helm repo update
helm install traefik traefik/traefik -n traefik --create-namespace \
  -f k8s/hetzner/platform/traefik-values.yaml
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.1/standard-install.yaml
kubectl get gatewayclass   # traefik → ACCEPTED: True
kubectl get pods -n traefik
```

## 10. cert-manager

`config.enableGatewayAPI=true` is required or the `gatewayHTTPRoute` solver is ignored.

```bash
helm repo add jetstack https://charts.jetstack.io && helm repo update
helm install cert-manager jetstack/cert-manager -n cert-manager --create-namespace \
  --set crds.enabled=true --set config.enableGatewayAPI=true
```

## 11. Build the `iaka` overlay

`app-multi` serves two domains and two frontends. Copy it and strip the `sichocolate.com` / `ui-demo` half. Run these in order — the whole block is verified to render.

```bash
cp -r k8s/hetzner/app-multi k8s/hetzner/iaka
cd k8s/hetzner/iaka
rm -f ui-demo-patch.yaml secrets-patch.yaml

grep -rl 'granite-security\.org' . | xargs sed -i '' 's/granite-security\.org/iaka.com/g'

# kustomization: drop the ui-demo image block (3 lines) first, then its
# resources/patches lines — deleting by /ui-demo/ alone orphans the block's
# newTag line onto the preceding image entry and breaks the YAML.
sed -i '' '/- name: granite-ui-demo/,+2d' kustomization.yaml
sed -i '' '/ui-demo/d' kustomization.yaml

# config-patch: sichocolate appears inline inside TRUSTED_JWT_ISSUERS and
# CORS_ALLOWED_ORIGINS, so those need substitution, not line deletion.
sed -i '' 's|https://sichocolate\.com/auth,||; s|,https://sichocolate\.com||; /SPA_CLIENT_CHOCOLATE/d' \
  config-patch.yaml

# production-patches: drop the ui-demo Deployment document, or the build fails
# with "no resource matches strategic merge patch Deployment.v1.apps/ui-demo".
awk 'BEGIN{RS="\n---\n"; ORS=""} !/name: ui-demo/{if(n++) print "\n---\n"; print}' \
  production-patches.yaml > t && mv t production-patches.yaml

# gateway: remove the chocolate (and grafana) LISTENERS first, line-level,
# inside the Gateway document...
awk '/^    - name: (https|http)-(chocolate|grafana)$/{skip=1; next} skip && /^    - name: /{skip=0} skip && /^(---|[a-z])/{skip=0} !skip' \
  gateway.yaml > t && mv t gateway.yaml

# ...then remove the chocolate HTTPRoute documents, matched by object name.
# Do NOT filter documents on the string "sichocolate" — it appears inside the
# Gateway object too, and that deletes the entire Gateway.
awk 'BEGIN{RS="\n---\n"; ORS=""} !/name: granite-(route|http-redirect)-chocolate/{if(n++) print "\n---\n"; print}' \
  gateway.yaml > t && mv t gateway.yaml
cd -
```

Two keys live in `k8s/base/config.yaml` and are **not** overridden by the overlay, so they still point at the old domain. `FRONTEND_ORIGIN` builds the password-reset links in outgoing email — wrong value means dead reset links. Append to `k8s/hetzner/iaka/config-patch.yaml` under `data:`:

```yaml
  FRONTEND_ORIGIN: "https://iaka.com"
  # Must be a domain verified in Resend, not necessarily iaka.com
  RESEND_FROM: "Iaka <no-reply@notify.iaka.com>"
```

Verify:

```bash
kubectl kustomize k8s/hetzner/iaka > /tmp/rendered.yaml   # must succeed
grep -c 'kind: Gateway' /tmp/rendered.yaml               # expect 1, not 0
grep -n 'granite-security\|sichocolate\|ui-demo' /tmp/rendered.yaml   # expect no output
```

Expected result: 20 Deployments, one Gateway with 8 listeners (`shop`, `media`, `s3`, `garage-ui` × http/https) and 8 HTTPRoutes.

## 12. ACME contact

```bash
sed -i '' 's/mr\.vrabie@gmail\.com/<your-email>/' k8s/hetzner/platform/cluster-issuer.yaml
kubectl apply -f k8s/hetzner/platform/cluster-issuer.yaml
```

## 13. Secrets

`.gitignore` names each overlay's secrets file by explicit path, so the new overlay is **not** covered until you add it. Do this before creating the file:

```bash
echo 'k8s/hetzner/iaka/secrets-patch.yaml' >> .gitignore
cp k8s/hetzner/iaka/secrets-patch.yaml.example k8s/hetzner/iaka/secrets-patch.yaml
git check-ignore -v k8s/hetzner/iaka/secrets-patch.yaml   # must print a match
```

### 13.1 Self-generated — no account needed

```bash
openssl rand -base64 32                          # db-postgres-password
openssl rand -base64 32                          # db-myuser-password
openssl rand -base64 32                          # oidc-client-secret-plain
htpasswd -bnBC 10 "" '<the plain value above>' | tr -d ':\n'   # oidc-client-secret-encoded, prefix with {bcrypt}
openssl rand -hex 32                             # garage-rpc-secret
openssl rand -hex 32                             # garage-admin-token
htpasswd -bnBC 10 "" '<webui password>' | tr -d ':\n'          # garage-webui-auth-user-pass, prefix with "admin:"
```

`oidc-client-secret-plain` and `-encoded` are the same secret in two forms — the plaintext the gateway sends, and the bcrypt hash auth-server compares it against. They must match or the gateway can't obtain tokens. `k8s/base` ships `{noop}` plaintext for kind; use `{bcrypt}` here.

`storage-s3-access-key` / `storage-s3-secret-key`: leave `CHANGE_ME`. Garage doesn't exist yet — step 16 generates them.

### 13.2 Google — federated login

**Where:** [console.cloud.google.com](https://console.cloud.google.com) → create or pick a project.

1. **APIs & Services → OAuth consent screen** → User type **External** → fill app name + support email. Scopes: `openid`, `.../auth/userinfo.profile`, `.../auth/userinfo.email` — that is exactly what `SecurityConfig.java:344` requests, and no others are needed. While the app is in *Testing*, only accounts you add under **Test users** can log in; **Publish app** to open it up.
2. **APIs & Services → Credentials → Create Credentials → OAuth client ID** → Application type **Web application**.
3. **Authorized redirect URIs** — add exactly:
   ```
   https://iaka.com/login/oauth2/code/google
   ```
   No "Authorized JavaScript origins" entry is needed; this is a server-side code flow. The path comes from `GOOGLE_REDIRECT_URI`'s default `{baseUrl}/login/oauth2/code/{registrationId}` (`SecurityConfig.java:107`) — a mismatch here surfaces as Google's `redirect_uri_mismatch` error page, not an app error.

| Google shows | Secret key |
|---|---|
| Client ID (`...apps.googleusercontent.com`) | `google-client-id` |
| Client secret | `google-client-secret` |

Skipping this: form login still works; the "Sign in with Google" button fails.

### 13.3 Stripe — payments

**Where:** [dashboard.stripe.com](https://dashboard.stripe.com). Use the **Test mode** toggle until you're ready for real charges — the key prefixes differ (`pk_test_`/`sk_test_` vs `pk_live_`/`sk_live_`) and test/live webhook secrets are separate.

1. **Developers → API keys** ([dashboard.stripe.com/apikeys](https://dashboard.stripe.com/apikeys)):

   | Stripe shows | Secret key |
   |---|---|
   | Publishable key `pk_…` | `stripe-publishable-key` |
   | Secret key `sk_…` (click Reveal) | `stripe-secret-key` |

2. **Developers → Webhooks → Add endpoint** ([dashboard.stripe.com/webhooks](https://dashboard.stripe.com/webhooks)):
   - Endpoint URL:
     ```
     https://iaka.com/api/payments/webhook
     ```
     (From `PaymentRoute.java:29`. It's `permitAll` in `PaymentSec.java:61` and the gateway passes it through unauthenticated, so Stripe reaches it without a token.)
   - Events to send — these three, and only these; the handler ignores everything else (`WebhookHandler.java:204-206`):
     ```
     payment_intent.succeeded
     payment_intent.payment_failed
     payment_intent.canceled
     ```
   - After creating, click **Reveal** on the **Signing secret** (`whsec_…`) → `stripe-webhook-secret`.

   The endpoint must be live and TLS-valid before Stripe will verify it, so create this **after** step 15. Until then, `POST /api/payments/intent/{orderId}/sync` advances payment status manually.

Skipping this: checkout fails at payment.

### 13.4 Resend — transactional email

**Where:** [resend.com](https://resend.com).

1. **Domains → Add Domain** → `notify.iaka.com` (a subdomain keeps your root domain's mail reputation separate). Resend gives you DKIM/SPF/DMARC records — add them at Cloudflare as **DNS-only**, then click **Verify**. This is the slow step; propagation can take up to a few hours.
2. **API Keys → Create API Key** → permission **Sending access**, domain-scoped to the one above → `resend-api-key` (shown once).
3. `RESEND_FROM` in `config-patch.yaml` (step 11) must use an address **on the verified domain** — `Iaka <no-reply@notify.iaka.com>`. Any other domain is rejected at send time.

Skipping this: set `resend-api-key: ""`. Welcome, password-changed, and password-reset emails are logged and no-op'd rather than sent — so self-service password reset is effectively unusable.

### 13.5 Apply

```bash
kubectl create namespace granite
kubectl apply -n granite -f k8s/hetzner/iaka/secrets-patch.yaml
```

Re-run these two after any later edit, then restart the affected deployment — env vars are read at startup only.

## 14. Images

Images come from Docker Hub (`moldovean/granite-*`) and the overlay pins SHAs already. Skip to step 15 to use them as-is.

To build your own, on Apple Silicon the `--platform linux/amd64` flag is required — a native arm64 image fails at container start with `exec format error`:

```bash
# Token from hub.docker.com → Account Settings → Personal access tokens
# → Generate, scope "Read & Write". Use a token, not your account password.
export DOCKERHUB_TOKEN=<token>
echo $DOCKERHUB_TOKEN | docker login -u <user> --password-stdin
export TAG=$(git rev-parse --short HEAD)

for s in auth-server gateway greetings shop payment profile delivery notification storage; do
  (cd $s && ./gradlew build -x test)
  docker buildx build --platform linux/amd64 -t docker.io/<user>/granite-$s:$TAG --push $s/
done
docker buildx build --platform linux/amd64 -t docker.io/<user>/granite-ui-shop:$TAG --push ui-shop/

sed -i '' -E "s|newTag: .*|newTag: $TAG|" k8s/hetzner/iaka/kustomization.yaml
```

Keep the Docker Hub repos public, or create an `imagePullSecret` — nothing in `production-patches.yaml` wires one up.

## 15. Deploy

```bash
kubectl config current-context   # must be iaka-admin
kubectl apply -k k8s/hetzner/iaka
kubectl -n granite wait --for=condition=ready pod -l app=postgres-auth --timeout=120s
kubectl -n granite wait --for=condition=ready pod -l app=kafka --timeout=120s
kubectl -n granite wait --for=condition=ready pod -l app=auth-server --timeout=180s
kubectl -n granite wait --for=condition=ready pod -l app=gateway --timeout=180s
kubectl -n granite get pods
```

The site is live from here. Now go back and finish step 13.3's Stripe webhook — Stripe verifies the endpoint at creation time, so it could not be registered before this point. Re-apply the secret and roll payment afterwards:

```bash
kubectl apply -n granite -f k8s/hetzner/iaka/secrets-patch.yaml
kubectl -n granite rollout restart deployment payment
```

## 16. Bootstrap Garage

```bash
kubectl -n granite exec -it deploy/garage -- /garage status          # copy the node id
kubectl -n granite exec -it deploy/garage -- /garage layout assign -z dc1 -c 5G <node-id>
kubectl -n granite exec -it deploy/garage -- /garage layout apply --version 1
kubectl -n granite exec -it deploy/garage -- /garage key create storage-key
kubectl -n granite exec -it deploy/garage -- /garage bucket create media.iaka.com
kubectl -n granite exec -it deploy/garage -- /garage bucket allow --read --write media.iaka.com --key storage-key
kubectl -n granite exec -it deploy/garage -- /garage bucket website --allow media.iaka.com
```

Paste the `key create` output into `secrets-patch.yaml` as `storage-s3-access-key` / `storage-s3-secret-key` — the secret is shown only once — then:

```bash
kubectl apply -n granite -f k8s/hetzner/iaka/secrets-patch.yaml
kubectl -n granite rollout restart deployment storage
```

CORS, from your machine (the garage image has no `aws` CLI):

```bash
kubectl -n granite port-forward deploy/garage 3900:3900 &
AWS_ACCESS_KEY_ID=<access-key> AWS_SECRET_ACCESS_KEY=<secret-key> \
  aws --endpoint-url http://localhost:3900 s3api put-bucket-cors --bucket media.iaka.com \
  --cors-configuration '{"CORSRules":[{"AllowedOrigins":["https://iaka.com"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedHeaders":["*"],"MaxAgeSeconds":3600}]}'
```

## 17. Split-horizon DNS

Without this, backend pods fetch `https://iaka.com/auth/.well-known/openid-configuration` by leaving the cluster and hairpinning back through Cloudflare. Not applied by `kubectl apply -k` — it is a separate step.

```bash
sed -i '' '/sichocolate\.override/,+4d; /grafana-granite\.override/,+4d' k8s/hetzner/platform/coredns-custom.yaml
sed -i '' 's/granite-security\.org/iaka.com/g; s/granite-security\.override/iaka.override/' k8s/hetzner/platform/coredns-custom.yaml

TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.spec.clusterIP}')
sed -i '' "s/10\.233\.35\.75/$TRAEFIK_IP/g" k8s/hetzner/platform/coredns-custom.yaml
kubectl apply -f k8s/hetzner/platform/coredns-custom.yaml
kubectl rollout restart deployment coredns -n kube-system
kubectl rollout status deployment coredns -n kube-system
```

Re-run this whenever Traefik's Service is recreated — the ClusterIP changes.

## 18. Verify

```bash
kubectl -n granite exec deploy/shop -- getent hosts iaka.com   # expect the Traefik ClusterIP
kubectl -n granite describe certificate iaka.com-tls           # expect Ready: True
curl -sI https://iaka.com/                                     # expect 200
curl -sI https://iaka.com/auth/.well-known/openid-configuration
```

Then in a browser: log in as `user`/`user` and confirm the JWT `iss` claim is `https://iaka.com/auth`.

## 19. Day-2

| Task | Command |
|---|---|
| Roll out a new image | Bump `newTag` in `k8s/hetzner/iaka/kustomization.yaml` → `kubectl apply -k k8s/hetzner/iaka` |
| Force a `:latest` re-pull | `kubectl -n granite rollout restart deployment <service>` |
| Logs | `kubectl -n granite logs -f deploy/<service>` |
| kafka-ui (no HTTPRoute by design) | `kubectl -n granite port-forward deploy/kafka-ui 8090:8080` |
| Resource pressure | `kubectl top pods -n granite` |
| Teardown (keeps cluster + Traefik) | `kubectl delete namespace granite` |

`kubectl delete -k` destroys the PVCs. Delete individual deployments instead when you want to keep Postgres/Kafka data.

## Notes

- `sed -i ''` is macOS. Drop the `''` on Linux.
- Single node, no HA, no backups. PVCs are `local-path` and tied to this node's disk.
- ArgoCD is optional: `k8s/hetzner/argocd/argocd-application.yaml`, repoint `path` at `k8s/hetzner/iaka`.
