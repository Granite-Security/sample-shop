# Prompt: generate an AWS IaC deployment plan for granite-security

Use this file as the prompt/brief for an LLM (Claude, etc.) whose job is to produce a
**plan document** (not code yet) for deploying this repo's `k8s/base` manifests to AWS. Paste
the "Task for the LLM" section below into a fresh session, or hand it the whole file.

The goal of that plan is to get from "runs in `kind` on my laptop" (see `k8s/kind/kind.md`) to
"runs on a real AWS-hosted Kubernetes cluster, reachable over a real domain with TLS", the same
way `cloud/hetzner/` already does for Hetzner. Nothing should be applied yet — the plan is
reviewed by the user first.

---

## Context to give the LLM

- App: `granite-security`, a multi-service demo (auth-server, gateway, greetings, shop, payment,
  delivery, profile, ui-shop, kafka, 5x postgres). All k8s-facing manifests already exist and are
  environment-agnostic in `k8s/base/` (Kustomize base). Two overlays already exist:
  `k8s/kind/` (local, NodePort) and `cloud/hetzner/` (a previous cloud target, Ingress-based —
  read `cloud/hetzner/app/kustomization.yaml`, `cloud/hetzner/platform/*.yaml` as the reference
  pattern for what "cloud overlay" means in this repo).
- Nothing in `k8s/base/` should need to change for a new cloud target — only a new overlay
  (`cloud/aws/app/`, `cloud/aws/platform/`) plus whatever AWS-specific infra (VPC, cluster,
  DNS, cert-issuer, node groups) the overlay depends on.
- `k8s/base/secrets.yaml` is git-ignored and must never be committed with real values — same
  constraint applies to any AWS secrets material (`secrets-patch.yaml`, IAM keys, etc.). Follow
  the `.example` file convention already used elsewhere in this repo.
- Stateful services (5x Postgres, Kafka) currently run as in-cluster Deployments with PVCs in
  `k8s/base/postgres.yaml` / `kafka.yaml`. The plan must explicitly decide, per stateful service,
  whether AWS deployment keeps them in-cluster (EBS-backed PVCs) or migrates them to managed
  services (RDS, MSK) — don't silently assume one over the other.

## Task for the LLM producing the plan

Write a plan (as markdown, saved to `cloud/aws/plan.md`) that a human can review before any
`terraform apply` / `eksctl` / `kubectl apply` is run. The plan must cover:

1. **Target shape** — which AWS Kubernetes option (EKS is the default assumption; call out if a
   cheaper/simpler alternative like a self-managed kubeadm cluster on EC2 is worth considering
   for a demo app, and state the tradeoff explicitly rather than picking silently).
2. **IaC tool choice** — Terraform vs CDK vs eksctl-only, with a one-line justification. Default
   to Terraform unless there's a stated reason not to, since it keeps the "declarative, reviewable
   diff before apply" property this task cares about.
3. **Infra inventory**, each as a named, separately-applyable unit: VPC/subnets, EKS cluster +
   node group(s), IAM roles (cluster, nodes, any IRSA needed for cert-manager/external-dns),
   ECR repositories (one per service image, mirroring the `ghcr.io/CHANGE_ME/granite-*` pattern in
   `cloud/hetzner/app/kustomization.yaml`), Route53 hosted zone / records, ACM or cert-manager +
   Let's Encrypt for TLS, an ingress controller (aws-load-balancer-controller vs ingress-nginx —
   decide and justify).
4. **Kustomize overlay design** for `cloud/aws/`, mirroring the existing `cloud/hetzner/`
   structure (`app/` for the workload overlay, `platform/` for cluster-level add-ons). List the
   specific patch files it will need (image registry rewrite, ingress host/TLS, any config
   overrides for issuer URLs the way `k8s/kind/config-patch.yaml` and
   `cloud/hetzner/app/config-patch.yaml` do) — don't write the YAML yet, just name each file and
   its one-sentence purpose.
5. **Stateful data decision** — explicit recommendation for Postgres x5 and Kafka: keep
   in-cluster (note the EBS StorageClass / PVC implications) or move to RDS/MSK (note the extra
   Terraform resources and connection-string/secret plumbing that implies). Pick one and say why,
   given this is a demo/portfolio app, not a production system with an existing data migration
   requirement.
6. **Secrets handling** — how `k8s/base/secrets.yaml` equivalents (Stripe keys, Google OAuth
   creds, DB passwords) get into the AWS cluster: options are Kubernetes Secrets applied by hand
   (matching the local `.example` pattern), AWS Secrets Manager + External Secrets Operator, or
   SOPS-encrypted files in-repo. Recommend one, with a one-line reason.
7. **Ordering / dependency graph** — the sequence infra must be created in (VPC → cluster → node
   groups → IRSA → controllers → ECR push → app overlay apply), so later phases don't get built
   before their prerequisites exist.
8. **Cost note** — a rough monthly order-of-magnitude for the recommended shape (EKS control
   plane + node group + NAT gateway + any managed DB/broker), since this is a demo app and
   ongoing cost is a real constraint, not an afterthought.
9. **Explicit non-goals** — call out what the plan is *not* doing (e.g., multi-region, HA
   Postgres, autoscaling policy tuning) so the user can decide later whether those matter.

## Constraints on the plan itself

- Output a plan, not Terraform/YAML — this step is about decisions and structure, review happens
  before any code is written or any `apply` is run.
- Every recommendation needs a one-line "why", not just a choice — this repo's existing docs
  (`k8s/troubleshooting.md`, `k8s/kind/kind.md`) consistently explain *why*, not just *what*; match
  that style.
- Flag every place a decision was made on the user's behalf (tool choice, managed-vs-in-cluster
  data, ingress controller) so they're easy to challenge in review — don't bury a consequential
  choice inside a bullet list where it reads as settled.
- Do not invent AWS account IDs, domain names, or region choices — leave these as explicit
  placeholders (`<AWS_ACCOUNT_ID>`, `<DOMAIN>`, `<REGION>`) for the user to fill in.
