# Hetzner node1 — common commands

Quick reference for manual deploys/rollouts against the `granite` namespace.


## Deploy


```bash
kubectl apply -k cloud/hetzner/app-multi/
```

If it fails with `metadata.resourceVersion: ... must be specified for an update`
on a resource, that resource's `kubectl.kubernetes.io/last-applied-configuration`
annotation is corrupted (usually from a stray `kubectl replace --save-config`).
Fix by dropping the annotation so apply can rebuild it cleanly, then re-apply:

```bash
kubectl annotate <kind> <name> -n granite kubectl.kubernetes.io/last-applied-configuration-
kubectl apply -k cloud/hetzner/app-multi/
```


```bash
kubectl rollout restart deployment -n granite -l tier=backend
kubectl rollout restart deployment -n granite -l tier=frontend
```

```bash
kubectl rollout restart deployment -n granite
```

A single service:

```bash
kubectl rollout restart deployment/<name> -n granite
```

## Rollout status

Watch a restart finish:

```bash
kubectl rollout status deployment -n granite -l tier=backend
kubectl rollout status deployment -n granite -l tier=frontend
kubectl rollout status deployment/<name> -n granite
```

## Rollout history / undo

```bash
kubectl rollout history deployment/<name> -n granite
kubectl rollout undo deployment/<name> -n granite
```

## Inspect

```bash
kubectl get pods -n granite -l tier=backend
kubectl get pods -n granite -l tier=frontend
kubectl get deployment -n granite
kubectl describe pod <pod> -n granite
kubectl logs -f deployment/<name> -n granite
```

## ArgoCD

The `granite` namespace is currently deployed by hand (`kubectl apply -k`
above), not managed by ArgoCD — `cloud/hetzner/argocd/argocd-application.yaml`
exists in-repo but has not been applied to the cluster. Check with:

```bash
kubectl get application granite -n argocd
kubectl get applications -n argocd
```
