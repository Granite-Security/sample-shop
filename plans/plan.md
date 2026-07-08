# Production deployment plan — Hetzner + kubespray + Cloudflare + Let's Encrypt

Goal: take the microservices currently running in `kind` (see `k8s/base` +
`k8s/kind`) and run them on a real Kubernetes cluster: one Hetzner VPS,
Kubernetes installed with kubespray, DNS on `granite-security.net` via
Cloudflare, TLS via Let's Encrypt (cert-manager).

The kustomize/platform manifests this plan produces live under `hetzner/`:

```
hetzner/
├── platform/                    # cluster add-ons, applied once with kubectl/helm
│   ├── ingress-nginx-values.yaml   # bare-metal hostNetwork ingress-nginx
│   ├── cluster-issuer.yaml         # cert-manager ClusterIssuer (Let's Encrypt)
│   └── coredns-custom.yaml         # split-horizon DNS for the prod domain
└── app/                          # the application overlay (kubectl apply -k hetzner/app)
    ├── kustomization.yaml
    ├── config-patch.yaml           # public-domain URLs instead of "gateway:8080"
    ├── ingress.yaml                # granite-security.net -> ui-shop:80
    ├── ui-shop-patch.yaml          # ClusterIP, drop the kind-only TLS volume
    ├── production-patches.yaml     # resource limits, imagePullPolicy, postgres password fix
    └── secrets-patch.yaml.example  # copy to secrets-patch.yaml (gitignored) and fill in
```

`hetzner/app` builds cleanly today (`kubectl kustomize hetzner/app`) once you've
copied `secrets-patch.yaml.example` → `secrets-patch.yaml` and filled in
placeholder values — verified against the current `k8s/base` manifests.

---

## 0. Things you need to do yourself first

- [ ] **Hetzner Cloud**: create an account, create an SSH key, provision one VPS.
  Recommended: **CPX31** (4 vCPU / 8GB / Ubuntu 24.04) minimum, **CPX41** (8 vCPU
  / 16GB) if you want headroom. This stack runs 7 JVM services + 5 Postgres
  instances + Kafka on one node — 8GB is workable but leaves little slack.
- [ ] **Cloudflare**: buy `granite-security.net`, add it to Cloudflare, note the
  nameservers and point the domain at them.
- [ ] **GitHub Container Registry**: since production nodes can't reuse kind's
  `imagePullPolicy: Never` + local image trick, you need a real registry. GHCR
  tied to your GitHub account is the path of least resistance — create a
  classic PAT with `write:packages` scope (and `read:packages` for pulling if
  the packages stay private).
- [ ] Decide, for now: keep Stripe in **test mode** until you're ready to accept
  real payments (switching to live keys is just editing `secrets-patch.yaml`
  later).

---

## 1. Harden the VPS

```bash
ssh root@<VPS_IP>
adduser deploy && usermod -aG sudo deploy
rsync --archive --chown=deploy:deploy ~/.ssh /home/deploy
```

Edit `/etc/ssh/sshd_config`: `PasswordAuthentication no`, `PermitRootLogin no`,
then `systemctl restart sshd`.

Firewall (ufw):

```bash
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 6443/tcp   # kube-apiserver
ufw enable
```

---

## 2. Install Kubernetes with kubespray

From your own machine (not the VPS):

```bash
git clone --depth 1 --branch v2.26.0 https://github.com/kubernetes-sigs/kubespray.git
cd kubespray
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt

cp -rfp inventory/sample inventory/hetzner
declare -a IPS=(<VPS_IP>)
CONFIG_FILE=inventory/hetzner/hosts.yaml python3 contrib/inventory_builder/inventory.py "${IPS[@]}"
```

Edit `inventory/hetzner/hosts.yaml` so the single node carries all three roles
(`kube_control_plane`, `kube_node`, `etcd`) — the sample generator does this
automatically for a one-host list. Set `ansible_user: deploy` and
`ansible_become: true` for that host.

```bash
ansible-playbook -i inventory/hetzner/hosts.yaml --become --become-user=root cluster.yml
```

Pull the kubeconfig to your machine and confirm:

```bash
scp deploy@<VPS_IP>:~/.kube/config ~/.kube/config-granite
export KUBECONFIG=~/.kube/config-granite
# rewrite the server: entry to https://<VPS_IP>:6443 if it's still 127.0.0.1
kubectl get nodes
```

---

## 3. Cluster add-ons

**Storage** — kubespray ships no default StorageClass, but the base manifests
(`k8s/base/postgres.yaml`, `k8s/base/kafka.yaml`) need PVCs to bind:

```bash
kubectl apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.30/deploy/local-path-storage.yaml
kubectl patch storageclass local-path -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
```

**ingress-nginx** — bare-metal, hostNetwork, using
`hetzner/platform/ingress-nginx-values.yaml`:

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm install ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-nginx --create-namespace \
  -f hetzner/platform/ingress-nginx-values.yaml
```

**cert-manager**:

```bash
helm repo add jetstack https://charts.jetstack.io
helm install cert-manager jetstack/cert-manager \
  -n cert-manager --create-namespace --set crds.enabled=true
kubectl apply -f hetzner/platform/cluster-issuer.yaml
```

---

## 4. DNS (Cloudflare)

Add an **A record**: `granite-security.net` → `<VPS_IP>`, proxy status **DNS
only** (grey cloud) for now. cert-manager's HTTP-01 challenge needs to reach
the node directly the first time; you can switch to proxied (orange cloud)
afterward once the cert is issued and renewing, if you want Cloudflare's CDN/WAF
in front.

---

## 5. Split-horizon DNS for the issuer URL

`hetzner/app/config-patch.yaml` sets `AUTH_SERVER_ISSUER` /
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` /
`OIDC_AUTHORITY` all to `https://granite-security.net/auth` — one shared
public hostname, same idea as the `kind` setup's "gateway:8080" trick (see the
comments in `k8s/base/config.yaml`). Backend pods fetch
`.well-known/openid-configuration` from that URL at startup to validate JWTs;
without help they'd hairpin out through the public internet and back in.
`hetzner/platform/coredns-custom.yaml` fixes that by resolving the hostname
straight to ingress-nginx's internal ClusterIP for anything running inside the
cluster. Apply it per the instructions in that file, after ingress-nginx is up
(you need its ClusterIP first).

**Verify this before going further** — it's the single most likely thing to
silently break login:

```bash
kubectl run -n granite dns-test --rm -it --image=curlimages/curl --restart=Never -- \
  curl -sv https://granite-security.net/auth/.well-known/openid-configuration
```

---

## 6. Build and push images

No registry-push tooling exists yet in this repo (each service just has a
`Dockerfile`). For each of `auth-server`, `gateway`, `greetings`, `shop`,
`payment`, `profile`, `delivery`, `ui-shop`:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <github-username> --password-stdin

TAG=$(git rev-parse --short HEAD)
for svc in auth-server gateway greetings shop payment profile delivery ui-shop; do
  docker build -t ghcr.io/<github-username>/granite-$svc:$TAG ./$svc
  docker push ghcr.io/<github-username>/granite-$svc:$TAG
done
```

Update `hetzner/app/kustomization.yaml`: replace `CHANGE_ME` in every `newName`
with your GitHub username/org, and set `newTag` to `$TAG`. If the GHCR packages
are private, create a pull secret and reference it from each Deployment (not
wired up here since it's a one-line addition once you know whether you're
keeping packages public or private):

```bash
kubectl create secret docker-registry ghcr-pull \
  -n granite --docker-server=ghcr.io \
  --docker-username=<github-username> --docker-password="$GITHUB_TOKEN"
```

---

## 7. Secrets

```bash
cp hetzner/app/secrets-patch.yaml.example hetzner/app/secrets-patch.yaml
```

Fill in real values (see the comments in that file for how to generate strong
DB passwords, a bcrypt-hashed OIDC client secret, and where to register the
production Google OAuth redirect URI / Stripe webhook). This file is gitignored
— it never gets committed, same convention as `k8s/base/secrets.yaml`.

---

## 8. Deploy

```bash
kubectl apply -k hetzner/app
kubectl get pods -n granite -w
```

Once everything is `Running`/`Ready`, check the cert issued:

```bash
kubectl get certificate -n granite
```

Then in a browser: `https://granite-security.net` — exercise the full login
flow (form login and Google), browse the shop, and place a test order to
confirm the payment → Kafka → delivery path works end to end.

---

## Redeploying after a code change

1. Build + push new images with a new tag (step 6).
2. Bump `newTag` for the changed service(s) in `hetzner/app/kustomization.yaml`.
3. `kubectl apply -k hetzner/app` — the pod template hash changes, so this
   triggers a real rollout. (Reusing a floating tag like `latest` with
   `imagePullPolicy: IfNotPresent` would silently *not* redeploy, since the
   node believes it already has that tag cached — that's why the overlay uses
   `IfNotPresent` + unique tags rather than `imagePullPolicy: Always`.)

---

## Known limitations / follow-ups (not implemented here)

- **Single node, no HA.** Every Deployment runs `replicas: 1`; a node reboot
  or OOM kill takes the whole stack down until pods reschedule.
- **Auth-server RSA keys regenerate on every restart** (per `CLAUDE.md`) — any
  restart invalidates every issued JWT, forcing all logged-in users to
  re-authenticate. Fine for a low-traffic launch; worth persisting the
  keypair (e.g. mount it from a Secret) before this matters.
- **No automated Postgres backups.** Add a `CronJob` running `pg_dump` per
  database to object storage, or rely on Hetzner's VPS snapshot feature as a
  stopgap.
- **No CI/CD.** Image build/push and `kubectl apply -k` are manual per the
  steps above; a GitHub Actions workflow would be the natural next step.
- **Secrets are plain `stringData` via a gitignored file, not encrypted at
  rest in git.** Fine for a single-operator setup; if this grows a team,
  look at Sealed Secrets or SOPS so encrypted secrets can actually be
  committed and reviewed.
- **`oidc-client-secret-encoded` uses Spring's `{noop}` (plaintext) encoder in
  `k8s/base`** — `secrets-patch.yaml.example` calls this out; use a real
  `{bcrypt}` hash in production.
