# Kubernetes

A Helm chart that runs the 3-node Aeron Cluster and 2 client pods. The Kubernetes files are in
[`infra/k8s/`](infra/k8s), and the Dockerfiles and the Docker Compose setup are in [`infra/docker/`](infra/docker). Run
every command below from the repository root.

| Path                                            | What it is                                                   |
|-------------------------------------------------|--------------------------------------------------------------|
| `infra/k8s/helm/aeron-auction/`                 | The chart                                                    |
| `infra/k8s/helm/aeron-auction/values.yaml`      | Defaults for a real cluster: 3 members on 3 different nodes  |
| `infra/k8s/helm/aeron-auction/values-local.yaml`| Overrides for a small local cluster with about 4GB of memory |
| `infra/k8s/load-images.sh`                      | Copies local images into the nodes of a kind-based cluster   |
| `infra/k8s/secrets/vault-local.sh`              | Installs Vault (dev mode) and External Secrets Operator locally |
| `infra/k8s/secrets/client-secrets.yaml`         | Syncs the client's secrets from Vault into a Kubernetes Secret |
| `infra/demo.sh`                                 | Runs one auction against http://localhost:8080               |

## What the chart creates

| Resource                                 | Why                                                                                         |
|------------------------------------------|---------------------------------------------------------------------------------------------|
| StatefulSet `<release>-cluster`, 3 pods  | One Aeron Cluster member per pod. `NODE_ID` is the pod ordinal (`-0`, `-1`, `-2`)          |
| Headless Service `<release>-cluster-headless` | Stable DNS names for the members. `publishNotReadyAddresses` lets them find each other before any is ready |
| PVC `data-<release>-cluster-N`           | The member's log, snapshots and archive at `/data`. Kept when the release is uninstalled   |
| Deployment `<release>-client`, 2 pods    | The REST API and web UI. Each pod advertises its own IP for egress and read replies         |
| Service `<release>-client`               | HTTP on port 8080. Only pods with a cluster session are ready and get traffic              |
| PodDisruptionBudgets                     | At most 1 member down at a time, so a quorum of 2 stays up; at least 1 client up           |
| Ingress, ServiceMonitors                 | Off by default: `client.ingress.enabled`, `metrics.serviceMonitor.enabled`                 |

The chart never creates a Secret. With `client.existingSecret` or `cluster.existingSecret` set, the pods read every key
of that Secret as an env var (see [Secrets with Vault](#secrets-with-vault)).

Members start together (`podManagementPolicy: Parallel`), since none of them can elect a leader alone. `/dev/shm` is a
memory-backed `emptyDir`, so it counts toward the pod's memory limit. The chart refuses an even `cluster.replicas`.
Don't change the replica count of an existing release: the member list is static and is baked into the log.

## Deploy locally

You need Java 25, Docker, `kubectl`, Helm, and a local Kubernetes cluster such as Docker Desktop, OrbStack, kind or
minikube.

**1. Build the jars and images.**

```bash
./mvnw clean package
docker build -f infra/docker/Dockerfile.cluster -t aeron-auction/cluster:latest .
docker build -f infra/docker/Dockerfile.client -t aeron-auction/client:latest .
```

**2. Make the images visible to the cluster.** `values-local.yaml` sets `pullPolicy: Never`, so the nodes must already
have them.

| Cluster                                  | What to do                                                              |
|------------------------------------------|-------------------------------------------------------------------------|
| Docker Desktop, kubeadm mode; OrbStack   | Nothing, the nodes use the Docker image store                           |
| Docker Desktop, kind mode                | `./infra/k8s/load-images.sh`                                                |
| kind                                     | `kind load docker-image aeron-auction/cluster:latest aeron-auction/client:latest` (or the script) |
| minikube                                 | `minikube image load aeron-auction/cluster:latest aeron-auction/client:latest` |

Run this again after every rebuild, then restart the pods (step 5).

**3. Install.**

```bash
helm install auction infra/k8s/helm/aeron-auction \
  -n auction --create-namespace \
  -f infra/k8s/helm/aeron-auction/values-local.yaml
kubectl -n auction wait --for=condition=Ready pod --all --timeout=180s
kubectl -n auction get pods -o wide
```

All 5 pods should be `1/1 Running`. A client becomes ready only once it has a session with the cluster's leader.

**4. Try it.**

```bash
./infra/demo.sh
```

The web UI is at http://localhost:8080 and Swagger at http://localhost:8080/swagger-ui.html. Stop the Docker Compose
stack first, because it also uses port 8080.

`values-local.yaml` makes the client Service a `LoadBalancer`, which some local clusters publish on `localhost`, so no
port-forward is needed:

| Cluster                    | http://localhost:8080 works?                                                              |
|----------------------------|-------------------------------------------------------------------------------------------|
| Docker Desktop, OrbStack   | Yes, as soon as `kubectl -n auction get svc` shows an `EXTERNAL-IP`                       |
| minikube                   | After `minikube tunnel`, left running in another terminal                                 |
| kind                       | After running [cloud-provider-kind](https://github.com/kubernetes-sigs/cloud-provider-kind); use the `EXTERNAL-IP` |
| Anything else              | `kubectl -n auction port-forward svc/auction-aeron-auction-client 8080:8080`              |

On a real cluster, keep the default `ClusterIP` and turn on the Ingress (`client.ingress.enabled`) instead.

**Failover.** Find the leader (`aeron_cluster_role` is `2` on the leader, `0` on followers), delete its pod, and keep
sending requests. A new leader takes over within a few seconds, and the deleted pod rejoins as a follower with the
same data.

```bash
for i in 0 1 2; do
  kubectl -n auction port-forward pod/auction-aeron-auction-cluster-$i 1910$i:9100 >/dev/null &
done
sleep 2
for i in 0 1 2; do curl -s localhost:1910$i/actuator/prometheus | grep '^aeron_cluster_role'; done
kill %1 %2 %3

kubectl -n auction delete pod auction-aeron-auction-cluster-<leader>
```

**5. Update after a code change.**

```bash
./mvnw clean package
docker build -f infra/docker/Dockerfile.cluster -t aeron-auction/cluster:latest .
docker build -f infra/docker/Dockerfile.client -t aeron-auction/client:latest .
./infra/k8s/load-images.sh   # only for kind-based clusters
kubectl -n auction rollout restart statefulset/auction-aeron-auction-cluster deployment/auction-aeron-auction-client
```

The StatefulSet replaces one member at a time, so the cluster keeps a quorum and its data throughout. When the leader's
turn comes there is an election, and requests return `503` for a few seconds (about 10-15s locally) until the clients
reconnect.

**6. Remove.**

```bash
helm uninstall auction -n auction
kubectl -n auction delete pvc --all   # deletes the cluster's data
kubectl delete namespace auction
```

### Why `values-local.yaml`

| Setting                      | Default (`values.yaml`) | Local                                            |
|------------------------------|-------------------------|--------------------------------------------------|
| `cluster.podAntiAffinity`    | `hard`, one member per node | `soft`, members may share a node (no real fault tolerance then) |
| Member memory request/limit  | 3Gi / 3Gi, `/dev/shm` 1Gi | 640Mi / 1Gi, `/dev/shm` 384Mi                  |
| Client memory request/limit  | 1.5Gi / 1.5Gi, `/dev/shm` 256Mi | 384Mi / 640Mi, `/dev/shm` 128Mi          |
| Data volume                  | 10Gi                    | 1Gi                                              |
| Image                        | `appVersion` tag, `IfNotPresent` | `latest`, `Never`                       |

## Dashboards

None is installed by the chart or by Docker Desktop, so there is no dashboard URL until you add one.

**Kubernetes Dashboard**, to browse pods, logs and resources:

```bash
helm repo add kubernetes-dashboard https://kubernetes.github.io/dashboard/
helm upgrade --install kubernetes-dashboard kubernetes-dashboard/kubernetes-dashboard \
  -n kubernetes-dashboard --create-namespace

# An admin login. Only do this on a local cluster.
kubectl -n kubernetes-dashboard create serviceaccount admin
kubectl create clusterrolebinding dashboard-admin --clusterrole=cluster-admin \
  --serviceaccount=kubernetes-dashboard:admin
kubectl -n kubernetes-dashboard create token admin   # prints the login token

kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard-kong-proxy 8443:443
```

Open https://localhost:8443, accept the self-signed certificate and paste the token. The token expires after about an
hour; run `create token` again for a new one.

[Headlamp](https://headlamp.dev) and [k9s](https://k9scli.io) (`brew install k9s`) are lighter: they use your
kubeconfig, so nothing is installed in the cluster and no token is needed.

**Grafana**, for the Aeron Auction dashboard that the Docker setup has at http://localhost:3000. Install
`kube-prometheus-stack` and turn on the chart's ServiceMonitors:

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace \
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false

helm upgrade auction infra/k8s/helm/aeron-auction -n auction \
  -f infra/k8s/helm/aeron-auction/values-local.yaml \
  --set metrics.serviceMonitor.enabled=true

kubectl -n monitoring get secret monitoring-grafana -o jsonpath='{.data.admin-password}' | base64 -d; echo
kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80
```

Open http://localhost:3000, log in as `admin` with that password, and import
`infra/docker/grafana/dashboards/auction.json`. The stack also comes with dashboards for the cluster's nodes and pods.
It needs about 1-1.5Gi more memory, which a 4GB local worker running the app may not have; give Docker Desktop more
memory, or use k9s instead.

## Configuration and environment variables

The chart sets the environment variables in the pod spec, plus the keys of an optional existing Secret. There is no
ConfigMap: the settings are few, and as long as they sit in the pod spec, changing one rolls the pods by itself.

| Kind                                              | Where                                                                  | Examples                                                          |
|---------------------------------------------------|------------------------------------------------------------------------|-------------------------------------------------------------------|
| Computed from the release, namespace and pod      | `templates/cluster-statefulset.yaml`, `templates/client-deployment.yaml` | `NODE_ID`, `CLUSTER_MEMBERS`, `READ_ENDPOINT`, `EGRESS_HOST`, `INGRESS_ENDPOINTS` |
| Taken from values                                 | `values.yaml`, `values-local.yaml`                                     | `RPC_TIMEOUT_MS`, `READS_THROUGH_LOG`, `JAVA_TOOL_OPTIONS`        |
| Anything else                                     | `cluster.extraEnv`, `client.extraEnv`                                  |                                                                   |
| Secrets                                           | The Secret named by `client.existingSecret` / `cluster.existingSecret` | `DEMO_SECRET` from Vault                                          |

If a key is set both in the pod spec and in the Secret, the pod spec wins, so a Secret can't override `NODE_ID` or
`CLUSTER_MEMBERS`.

ConfigMaps and Secrets belong to a namespace, so each environment can have its own. The recommended setup:

1. **One namespace and one values file per environment**, such as `values-dev.yaml`, `values-staging.yaml` and
   `values-prod.yaml`, all in Git:
   ```bash
   helm upgrade --install auction infra/k8s/helm/aeron-auction -n auction-prod -f values-prod.yaml
   ```
   Git holds the real configuration. Don't change it with `kubectl edit`.
2. **Leave the computed variables in the templates.** They depend on pod and namespace names, and a hand-written copy
   goes out of date.
3. **Non-secret settings go in a ConfigMap that the chart renders from values**, read with `envFrom: configMapRef`. Put
   a `checksum/config` annotation on the pod template so that a config change rolls the pods; without it they keep the
   old values.
4. **Secrets never go in a values file in Git.** The chart only takes the name of an existing Secret
   (`existingSecret`) and reads it with `envFrom: secretRef`. Create that Secret with External Secrets Operator (from
   Vault, AWS Secrets Manager or GCP Secret Manager), as in [Secrets with Vault](#secrets-with-vault), or keep it
   encrypted in Git with Sealed Secrets or SOPS and `helm-secrets`.
5. **Settings that must match on every member stay chart-wide**, such as `log-channel` and
   `leader-heartbeat-timeout-ms`. Don't set them per pod through `extraEnv`.
6. **For more automation, use GitOps**: one Argo CD or Flux application per environment, pointing at the chart and that
   environment's values file.

Point 4 is in the chart. Point 3 isn't, and isn't needed until there is a setting that the pod spec can't hold.

## Secrets with Vault

Secrets live in Vault. External Secrets Operator (ESO) copies them into a Kubernetes Secret in the app's namespace,
and the chart reads that Secret through `existingSecret`. The app never talks to Vault, so moving to a real Vault only
changes the `SecretStore`.

```
Vault secret/auction/client ──ESO, every 1m──▶ Secret auction-client-secrets ──envFrom──▶ client pods
```

ESO logs in to Vault with **Kubernetes auth**: it presents a token of the ServiceAccount `vault-reader` (audience
`vault`), and Vault maps it to the role `auction`, which may only read `secret/auction/*`. No Vault token is stored in
the cluster.

**1. Install Vault and ESO.** The app must already be installed (step 3 of [Deploy locally](#deploy-locally)), because
the setup is for its namespace.

```bash
./infra/k8s/secrets/vault-local.sh          # namespace auction; pass another one as the first argument
```

This installs Vault 2.0 in dev mode in namespace `vault` and ESO in `external-secrets`, both pinned and with small
memory requests (about 200Mi in all). It turns on Kubernetes auth, writes the `auction-read` policy and the `auction`
role, and puts a demo value at `secret/auction/client` (`DEMO_SECRET=hello-from-vault`).

**2. Sync the secret and point the client at it.**

```bash
kubectl -n auction apply -f infra/k8s/secrets/client-secrets.yaml
kubectl -n auction get externalsecret auction-client-secrets   # STATUS SecretSynced, READY True

helm upgrade auction infra/k8s/helm/aeron-auction -n auction \
  -f infra/k8s/helm/aeron-auction/values-local.yaml \
  --set client.existingSecret=auction-client-secrets

kubectl -n auction exec deploy/auction-aeron-auction-client -- printenv DEMO_SECRET   # hello-from-vault
```

Pass the same `--set` on every later `helm upgrade`, or add `existingSecret: auction-client-secrets` under `client:`
in your values file. Without it the upgrade drops the secret from the pods.

**3. Add or change a secret.**

```bash
kubectl -n vault exec vault-0 -- env VAULT_TOKEN=root \
  vault kv put secret/auction/client DEMO_SECRET=new-value DB_PASSWORD=s3cret
kubectl -n auction annotate externalsecret auction-client-secrets force-sync=$(date +%s) --overwrite  # or wait 1m
kubectl -n auction rollout restart deployment/auction-aeron-auction-client
```

`vault kv put` replaces every key at that path, so list all of them. Env vars are read once at startup, so the pods
need the restart to see the new value. [Reloader](https://github.com/stakater/Reloader) can do that restart for you.

The Vault UI is at http://localhost:8200 with token `root`, after
`kubectl -n vault port-forward svc/vault 8200:8200`.

**Things to know about dev mode.** Vault keeps everything in memory, unseals itself and has a fixed root token. When
the `vault-0` pod restarts, every secret, the policy and the role are gone. ESO then can't refresh, but the Kubernetes
Secret keeps its last values, so running pods carry on. Run `vault-local.sh` again and put your secrets back. Never use
dev mode outside a local cluster.

**For a real cluster**, keep the same `client-secrets.yaml` and change:

- `server` to the real Vault's `https://` URL, with `caProvider` or `caBundle` for its certificate.
- On the Vault side, run the same `auth enable kubernetes`, `auth/kubernetes/config`, policy and role as in
  `vault-local.sh`, with one role per namespace (for example `bound_service_account_namespaces=auction-prod`).
- Use a `ClusterSecretStore` instead of a `SecretStore` if several namespaces share one Vault login.

Vault has been under the BSL license since 1.15. [OpenBao](https://openbao.org) is an open-source fork with the same
API, and works with ESO in the same way.

**Remove.**

```bash
helm upgrade auction infra/k8s/helm/aeron-auction -n auction -f infra/k8s/helm/aeron-auction/values-local.yaml
kubectl -n auction delete -f infra/k8s/secrets/client-secrets.yaml   # also deletes Secret auction-client-secrets
helm uninstall external-secrets -n external-secrets
helm uninstall vault -n vault
kubectl delete namespace vault external-secrets
```

The `helm upgrade` without `existingSecret` comes first: a client pod that names a missing Secret doesn't start.

## Deploy to a real cluster

Push the images to a registry the cluster can pull from, then point the chart at them:

```bash
helm install auction infra/k8s/helm/aeron-auction -n auction --create-namespace \
  --set cluster.image.repository=registry.example.com/aeron-auction/cluster \
  --set client.image.repository=registry.example.com/aeron-auction/client \
  --set cluster.image.tag=0.0.1 --set client.image.tag=0.0.1
```

With the default `podAntiAffinity: hard` the cluster needs at least 3 schedulable nodes. Pod-to-pod UDP must be
allowed between the members and clients (ports 9002-9006, 8010 and 9007), including by any NetworkPolicy. Set
`clusterDomain` if your cluster's DNS suffix isn't `cluster.local`.
