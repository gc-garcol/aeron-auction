#!/usr/bin/env bash
# Copies locally built images into the containerd of every node of the current Kubernetes context.
# Needed for kind-based clusters (Docker Desktop in kind mode, plain kind), whose nodes don't share the Docker image
# store. Not needed for Docker Desktop in kubeadm mode or OrbStack, which already see local images.
#
# Usage: infra/k8s/load-images.sh [image ...]   (default: aeron-auction/cluster:latest aeron-auction/client:latest)
set -euo pipefail

if [ "$#" -eq 0 ]; then
  set -- aeron-auction/cluster:latest aeron-auction/client:latest
fi

TAR="$(mktemp -t aeron-images).tar"
trap 'rm -f "$TAR"' EXIT

echo "saving $*"
docker save -o "$TAR" "$@"

for node in $(kubectl get nodes -o name); do
  node="${node#node/}"
  echo "loading into $node"
  # kubectl debug starts a pod on the node with its root filesystem mounted at /host.
  pod="$(kubectl debug "node/$node" -q --image=busybox:1.36 --profile=sysadmin --attach=false -- sleep 600 \
    | sed -n 's/.*\(node-debugger-[^ ]*\).*/\1/p')"
  if [ -z "$pod" ]; then
    pod="$(kubectl get pods -o name --sort-by=.metadata.creationTimestamp --field-selector "spec.nodeName=$node" \
      | grep node-debugger | tail -1)"
    pod="${pod#pod/}"
  fi
  kubectl wait --for=condition=Ready "pod/$pod" --timeout=120s >/dev/null
  kubectl cp "$TAR" "$pod:/host/tmp/aeron-images.tar"
  kubectl exec "$pod" -- chroot /host ctr -n k8s.io images import /tmp/aeron-images.tar
  kubectl exec "$pod" -- rm -f /host/tmp/aeron-images.tar
  kubectl delete pod "$pod" --wait=false >/dev/null
done
echo "done"
