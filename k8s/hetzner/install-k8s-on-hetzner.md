# Step by step: Installing Kubernetes with Kubespray on Hetzner

This is the server from : Davide Listello

Target server: `88.99.149.31` (Debian 11/12, single node — combined control-plane + worker)

You currently have `kind` installed locally. This guide keeps the Hetzner cluster's
kubeconfig separate from `kind`'s, using contexts, so both coexist without conflicts.

---

## 0. Prerequisites on your local machine

- `ssh`
- `git`
- `python3` and `pip`
- `kubectl`

### 0.1 Install kubectl (if missing)

Check first:

```bash
kubectl version --client
```

If it's not installed (you may already have it via `kind`'s dependencies, but it's not
guaranteed):

**macOS (Homebrew, recommended):**

```bash
brew install kubectl
```

**macOS/Linux (direct binary, if you don't use Homebrew):**

```bash
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/darwin/arm64/kubectl"
chmod +x kubectl
sudo mv kubectl /usr/local/bin/kubectl
```

(Use `darwin/amd64` instead of `darwin/arm64` if you're on an Intel Mac, or
`linux/amd64` / `linux/arm64` on Linux.)

Verify:

```bash
kubectl version --client
```

---

## 1. Secure initial access: SSH keys + a non-root admin user

Best practice is to avoid using `root` over SSH long-term, and to avoid password auth.
We'll create a dedicated sudo user with key-based auth, then lock down SSH.

### 1.1 Generate a local SSH key (if you don't already have one you want to use)

```bash
ssh-keygen -t ed25519 -C "davide-k8s" -f ~/.ssh/davide_k8s
```

### 1.2 Copy your public key to the server (using the root password you have)

```bash
ssh-copy-id -i ~/.ssh/davide_k8s.pub root@88.99.149.31
```

If `ssh-copy-id` isn't available, do it manually:

```bash
cat ~/.ssh/davide_k8s.pub | ssh root@88.99.149.31 "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
```

### 1.3 Log in as root with the key to confirm it works

```bash
ssh -i ~/.ssh/davide_k8s root@88.99.149.31
```

### 1.4 Create a dedicated admin user with sudo (do NOT run Kubespray as root directly)

On the server, as root:

```bash
adduser k8sadmin
usermod -aG sudo k8sadmin
```

Set up SSH key access for that user too:

```bash
rsync --archive --chown=k8sadmin:k8sadmin ~/.ssh /home/k8sadmin
```

Allow passwordless sudo for Ansible/Kubespray automation (recommended by Kubespray docs):

```bash
echo "k8sadmin ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/k8sadmin
```

### 1.5 Confirm the new user works

From your local machine:

```bash
ssh -i ~/.ssh/davide_k8s k8sadmin@88.99.149.31
sudo whoami   # should print "root"
```

### 1.6 Harden SSH (best practice)

On the server, edit `/etc/ssh/sshd_config`:

```
PermitRootLogin no
PasswordAuthentication no
```

Then restart SSH:

```bash
sudo systemctl restart sshd
```

From now on, always connect as `k8sadmin` using the key.

### 1.7 (Recommended) Basic firewall

Kubespray/Kubernetes needs several ports open between nodes, but since this is a single
node, you mainly need to expose SSH and the Kubernetes API (6443) plus whatever your
workloads need:

```bash
sudo apt update
sudo apt install -y ufw
sudo ufw allow OpenSSH
sudo ufw allow 6443/tcp
sudo ufw enable
```

**Important:** `ufw`'s default policy denies routed/forwarded traffic
(`Default: ... deny (routed)`), which blocks the pod-to-service-network traffic that
Calico and kube-proxy need even on a single node (e.g. `calico-kube-controllers` and
`coredns` will fail to reach the internal service ClusterIP and crash-loop with i/o
timeouts). Calico already enforces its own network policy via iptables, so allow
forwarding at the `ufw` level:

```bash
sudo sed -i 's/DEFAULT_FORWARD_POLICY="DROP"/DEFAULT_FORWARD_POLICY="ACCEPT"/' /etc/default/ufw
sudo ufw reload
```

**Also important:** even with forwarding fixed, `ufw`'s default **`INPUT`** policy
(`DROP`) breaks pod-to-service traffic on its own. `kube-proxy`'s `ipvs` mode does its
ClusterIP → real-server translation via a low-priority netfilter hook that runs *after*
the `iptables filter/INPUT` chain. Calico/kube-proxy's own rules only `RETURN` for
traffic to known service IPs (deferring the decision to that later `ipvs` hook) rather
than `ACCEPT` it outright — so if nothing else explicitly accepts it first, `ufw`'s
`INPUT` policy silently drops the packet before `ipvs` ever gets a chance to process it.
This is why traffic *you* originate on the host (e.g. `curl` from an SSH session) works
fine (that uses the `OUTPUT` chain, which stays `ACCEPT`), while pods calling any
ClusterIP time out. Allow the cluster's internal pod/service CIDRs explicitly (adjust if
you changed `kube_service_addresses` / `kube_pods_subnet` from the Kubespray defaults):

```bash
sudo ufw allow to 10.233.0.0/18 comment 'k8s service+pod CIDR - allow pod-to-service traffic'
```

Do all of this **before** running the Kubespray playbook (step 5) to avoid pods
crash-looping on first boot.

---

## 2. Install Ansible and clone Kubespray (on your local machine)

Kubespray is driven from your local machine via Ansible/SSH — you don't install
anything Kubernetes-specific on the server manually.

```bash
cd ~/code   # or wherever you keep repos
git clone https://github.com/kubernetes-sigs/kubespray.git
cd kubespray
git checkout v2.31.0   # latest stable release tag as of writing
```

Set up a Python virtualenv and install the pinned Ansible requirements (best practice —
avoids version mismatches):

```bash
python3 -m venv venv
source venv/bin/activate
pip install -U pip
pip install -r requirements.txt
```

---

## 3. Configure the inventory for your single node

```bash
cp -rfp inventory/sample inventory/hetzner
```

Edit `inventory/hetzner/inventory.ini` to look like this (single node acting as
control-plane, etcd, and worker):

```ini
[all]
node1 ansible_host=88.99.149.31 ip=88.99.149.31 ansible_user=k8sadmin ansible_ssh_private_key_file=~/.ssh/davide_k8s

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

### 3.1 Recommended variable tweaks

Edit `inventory/hetzner/group_vars/k8s_cluster/k8s-cluster.yml`:

- Leave `kube_network_plugin: calico` (solid default, works well on a single node).
- Set `kube_proxy_mode: ipvs` for better performance (optional but common best practice).

Edit `inventory/hetzner/group_vars/all/all.yml`:

- Optionally set `ntp_enabled: true` to keep node clocks synced (important for
  certificate validity in Kubernetes).

For a single-node combined control-plane/worker, also make sure the control-plane isn't
tainted so it can run workloads. In
`inventory/hetzner/group_vars/k8s_cluster/k8s-cluster.yml` (or `addons.yml` depending on
version), set:

```yaml
kube_node_taints:
  - key: node-role.kubernetes.io/control-plane
    effect: NoSchedule
    state: absent
```

(Newer Kubespray versions leave the control-plane untainted by default when
`kube_control_plane` and `kube_node` are the same host — double check by inspecting
`node1` taints after install, see step 5.)

---

## 4. Test connectivity before the real run

```bash
ansible -i inventory/hetzner/inventory.ini -m ping all
```

You should see a `SUCCESS` pong response. Fix any SSH/sudo issues before continuing.

---

## 5. Run the Kubespray playbook

This provisions the whole cluster — kubelet, containerd, etcd, control plane,
networking (Calico), etc. Takes roughly 10–20 minutes on a single small node.

```bash
ansible-playbook -i inventory/hetzner/inventory.ini --become --become-user=root cluster.yml
```

If it fails partway through (e.g. transient network blip), it's safe to re-run — the
playbook is idempotent.

---

## 6. Fetch the kubeconfig and merge it alongside `kind`

Kubespray writes an admin kubeconfig on the control-plane node at
`/etc/kubernetes/admin.conf`. Copy it down and merge it into your local kubeconfig as a
**named context**, so it doesn't clobber your existing `kind-*` contexts.

`admin.conf` is root-owned, so a plain `scp` as `k8sadmin` will fail with "Permission
denied" (sudo doesn't apply to the remote file read `scp` does). Instead, read it
through `ssh` with `sudo` and redirect the output locally:

```bash
ssh -i ~/.ssh/davide_k8s k8sadmin@88.99.149.31 "sudo cat /etc/kubernetes/admin.conf" > ~/.kube/davide-hetzner.yaml
```

Fix the server address (Kubespray usually sets it to the node's internal/public IP
already, but confirm) and rename the context/cluster/user so they're clearly labeled.

**Important:** Kubespray gives every cluster the same default cluster/user names
(`cluster.local` / `kubernetes-admin`), regardless of which server it's on. If you
already have another Hetzner (or any Kubespray-built) cluster in your kubeconfig, it
almost certainly uses those same default names. Renaming only the *context* is not
enough — the context still points at cluster/user objects with the generic names, which
will collide with the other cluster's objects during the merge (the first file listed
in `KUBECONFIG` wins on name conflicts, so your new context silently ends up pointing at
the *old* cluster's data). Rename **all three** — cluster, user, and context — to
something unique:

```bash
kubectl --kubeconfig ~/.kube/davide-hetzner.yaml config rename-context kubernetes-admin@cluster.local davide-hetzner-admin
```

Then open `~/.kube/davide-hetzner.yaml` directly and rename the `cluster.local` cluster
entry to `davide-hetzner` and the `kubernetes-admin` user entry to
`davide-hetzner-admin` (updating the `cluster:`/`user:` references under `contexts:` to
match). Confirm the names are actually unique against what's already in your main
kubeconfig first:

```bash
kubectl config view -o jsonpath='{range .clusters[*]}{.name}{"\n"}{end}'
kubectl config view -o jsonpath='{range .users[*]}{.name}{"\n"}{end}'
```

Merge it with your existing kubeconfig (which already has your `kind` contexts) without
overwriting anything:

```bash
KUBECONFIG=~/.kube/config:~/.kube/davide-hetzner.yaml kubectl config view --flatten > /tmp/merged-config
mv /tmp/merged-config ~/.kube/config
chmod 600 ~/.kube/config
```

Verify both clusters are visible:

```bash
kubectl config get-contexts
```

You should see your `kind-*` context(s) alongside `davide-hetzner-admin`.

Switch between them with:

```bash
kubectl config use-context davide-hetzner-admin   # to talk to the Hetzner cluster
kubectl config use-context kind-<name>     # back to kind
```

---

## 7. Verify the cluster

```bash
kubectl --context davide-hetzner-admin get nodes -o wide
kubectl --context davide-hetzner-admin get pods -A
```

All system pods (kube-system, calico, coredns, etc.) should reach `Running`.

---

## 8. Ongoing best practices

- **Keep the Kubespray repo checked out at the tag you deployed with** — use the same
  checkout to run `upgrade-cluster.yml` when you need to update Kubernetes versions,
  rather than mixing versions.
- **Back up `/etc/kubernetes/admin.conf` and etcd** periodically (`etcdctl snapshot
  save` on the node) — with a single-node cluster there's no HA, so this is your only
  recovery path.
- **Don't edit the server by hand** — make changes via Kubespray inventory vars and
  re-run the playbook, so the server stays reproducible.
- **Rotate/limit the admin kubeconfig** — treat `~/.kube/davide-hetzner.yaml` /
  `admin.conf` as a root-equivalent credential; keep it out of git.
- **Keep `ufw` rules tight** — only open ports you actually need beyond SSH/6443
  (e.g. 80/443 if you add an ingress controller).
