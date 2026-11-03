#!/usr/bin/env bash
# Sets up secrets for a local cluster: Vault in dev mode, External Secrets Operator, and the Vault side of the
# Kubernetes login that ESO uses to read the app's secrets. Safe to run again, for example after Vault restarts.
#
# Dev mode keeps everything in memory, unseals itself and has root token "root". When the Vault pod restarts, its
# data is gone: run this script again and put the secrets back. Never use dev mode outside a local cluster.
#
# Usage: infra/k8s/secrets/vault-local.sh [app-namespace]   (default: auction)
set -euo pipefail

NS="${1:-auction}"
VAULT_CHART_VERSION=0.34.1
ESO_CHART_VERSION=2.14.0

helm repo add hashicorp https://helm.releases.hashicorp.com >/dev/null
helm repo add external-secrets https://charts.external-secrets.io >/dev/null
helm repo update hashicorp external-secrets >/dev/null

echo "installing Vault (dev mode)"
helm upgrade --install vault hashicorp/vault --version "$VAULT_CHART_VERSION" \
  -n vault --create-namespace \
  --set server.dev.enabled=true \
  --set server.dev.devRootToken=root \
  --set injector.enabled=false \
  --set server.resources.requests.cpu=50m \
  --set server.resources.requests.memory=64Mi \
  --set server.resources.limits.memory=256Mi \
  --wait

echo "installing External Secrets Operator"
helm upgrade --install external-secrets external-secrets/external-secrets --version "$ESO_CHART_VERSION" \
  -n external-secrets --create-namespace \
  --set resources.requests.cpu=20m --set resources.requests.memory=48Mi \
  --set webhook.resources.requests.cpu=10m --set webhook.resources.requests.memory=32Mi \
  --set certController.resources.requests.cpu=10m --set certController.resources.requests.memory=32Mi \
  --wait

kubectl -n vault wait --for=condition=Ready pod/vault-0 --timeout=120s >/dev/null
vault() { kubectl -n vault exec -i vault-0 -- env VAULT_TOKEN=root vault "$@"; }

echo "configuring Kubernetes auth for namespace $NS"
# Vault checks ESO's ServiceAccount tokens with the Kubernetes API, using its own ServiceAccount.
vault auth list | grep -q '^kubernetes/' || vault auth enable kubernetes
vault write auth/kubernetes/config kubernetes_host=https://kubernetes.default.svc >/dev/null

# Read-only access to secret/auction/* and nothing else.
vault policy write auction-read - <<'EOF' >/dev/null
path "secret/data/auction/*" {
  capabilities = ["read"]
}
EOF

# Tokens of ServiceAccount "vault-reader" in the app namespace log in as role "auction".
vault write auth/kubernetes/role/auction \
  bound_service_account_names=vault-reader \
  bound_service_account_namespaces="$NS" \
  audience=vault \
  policies=auction-read \
  ttl=1h >/dev/null

if ! vault kv get secret/auction/client >/dev/null 2>&1; then
  echo "seeding secret/auction/client with a demo value"
  vault kv put secret/auction/client DEMO_SECRET=hello-from-vault >/dev/null
fi

echo "done. Next: kubectl apply -f infra/k8s/secrets/client-secrets.yaml"
