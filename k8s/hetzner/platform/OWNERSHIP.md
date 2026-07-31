# This directory is shared cluster infrastructure, not Granite application config

Everything in `k8s/hetzner/platform/` configures components that **every tenant on this
cluster depends on** — not just Granite. Changing or deleting a file here can take down
applications owned by other teams, in other repositories, with no reference to Granite
anywhere in their manifests.

Consumers of these components, verified against the live cluster on 2026-07-30:

```
veracrm     adiwave.com, adiwave.group
granite     granite-security.org, sichocolate.com
me-funnel   me.adiwave.group
default     registry.adiwave.com
```

## Why this file exists

Ownership of shared infrastructure is not discoverable from a cluster. You can read a
controller, its workload and its namespace, but **nothing in Kubernetes records which
repository installed it.** That fact lives in someone's memory until it is written down,
and forgetting it is exactly how several unrelated products came to depend on a Traefik
installation that no consuming repository mentions.

## Current state — transfer is planned but has NOT happened

The co-governed platform repository is:

```
adiwavegroup/hetzner-platform-infra-devops        (canonical, Argo CD reconciles from here)
Granite-Security/hetzner-platform-infra-devops    (co-governed copy; follows, does not lead)
```

That repository's `archdesign/infrastructure-ownership.yaml` records, for Traefik:

```yaml
currentOwner: sample-shop/k8s/hetzner/platform/traefik-values.yaml
targetOwner:  adiwavegroup/hetzner-platform-infra-devops
status:       migration-required
notes: >
  hostNetwork binds the node's :80/:443 directly, so only ONE controller can exist on
  this single-node cluster. Ownership must be transferred, never duplicated.
```

**`currentOwner` is still this directory.** The platform repository holds documentation and
read-only discovery snapshots; it does not yet deploy Traefik or cert-manager.

## Therefore: do not delete these files yet

It is tempting to remove them on the grounds that "the platform repo owns this now." It
does not — not yet. Deleting them today would remove the only record of the configuration
that terminates TLS for every public hostname on this cluster, before anything else is in a
position to own it.

Removal is the **last** step of the transfer, not the first:

1. Platform repo takes over deploying the component and reconciles it
2. Live state verified unchanged for every consumer above, not only Granite
3. `infrastructure-ownership.yaml` updated: `currentOwner` → platform repo, `status: transferred`
4. **Then** these files are deleted here, with the PR linking the transfer

Only one Traefik can exist — `hostNetwork` binds the node's `:80`/`:443` directly. If both
repositories deploy it during a transfer, they fight over a single port binding on a
single-node cluster. Sequence matters more than speed.

## Changing anything here needs both organizations

Per the platform repo's `archdesign/governance.md`, changes to Traefik, the `GatewayClass`,
cert-manager or the `letsencrypt-prod` ClusterIssuer require review from both organizations,
and the acceptance evidence must cover **every** consuming application — not only the one
that prompted the change. A change is not proven because the tenant who made it still works.

## What is safe to change here without cross-org review

Nothing in this directory. Granite's own routing lives in `k8s/hetzner/app-multi/` — Gateway,
HTTPRoutes, Services and workloads in the `granite` namespace are Granite's to change freely.
