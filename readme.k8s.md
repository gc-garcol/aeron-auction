# Kubernetes

A Helm chart that runs the 3-node Aeron Cluster, 2 client pods and Centrifugo for live updates. The Kubernetes files are in
[`infra/k8s/`](infra/k8s), and the Dockerfiles and the Docker Compose setup are in [`infra/docker/`](infra/docker). Run
every command below from the repository root.

| Path                                            | What it is                                                   |
|-------------------------------------------------|--------------------------------------------------------------|
| `infra/k8s/helm/aeron-auction/`                 | The chart                                                    |
| `infra/k8s/helm/aeron-auction/values.yaml`      | Defaults for a real cluster: 3 members on 3 different nodes  |
| `infra/k8s/helm/aeron-auction/values-local.yaml`| Overrides for a small local cluster with about 4GB of memory |
| `infra/k8s/helm/aeron-auction/values-centrifugo-external.yaml` | Uses a Centrifugo in its own namespace instead of the built-in one |
| `infra/k8s/centrifugo/values.yaml`, `values-local.yaml` | Values for the official Centrifugo chart, installed in its own namespace |
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
| Deployment `<release>-centrifugo`, 1 pod, its Service and ConfigMap | Pushes live updates to the web UI over WebSocket as binary protobuf frames. The clients publish to its server API at `:8000/api` |
| Ingress, ServiceMonitors                 | Off by default: `client.ingress.enabled`, `metrics.serviceMonitor.enabled`. The Ingress also routes `/connection` to Centrifugo |

The chart never creates a Secret. With `client.existingSecret` or `cluster.existingSecret` set, the pods read every key
of that Secret as an env var (see [Secrets with Vault](#secrets-with-vault)). Centrifugo needs one too, named by
`centrifugo.existingSecret`, and the install stops with an error until it's set.

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

**3. Install.** First create Centrifugo's Secret, which `values-local.yaml` names `auction-centrifugo`. Centrifugo reads
all three keys, and the client pods read the API key to publish.

```bash
kubectl create namespace auction
kubectl -n auction create secret generic auction-centrifugo \
  --from-literal=CENTRIFUGO_HTTP_API_KEY="$(openssl rand -hex 32)" \
  --from-literal=CENTRIFUGO_ADMIN_PASSWORD="$(openssl rand -hex 16)" \
  --from-literal=CENTRIFUGO_ADMIN_SECRET="$(openssl rand -hex 32)"

helm install auction infra/k8s/helm/aeron-auction \
  -n auction \
  -f infra/k8s/helm/aeron-auction/values-local.yaml
kubectl -n auction wait --for=condition=Ready pod --all --timeout=180s
kubectl -n auction get pods -o wide
```

All 6 pods should be `1/1 Running`. A client becomes ready only once it has a session with the cluster's leader.

Or with [k9s](#dashboards): run `k9s -n auction` and watch the pods turn `1/1 Running`. Press `l` on a pod that stays
`0/1` to see why.

To install without live updates, skip the Secret and add `--set centrifugo.enabled=false`. The web UI then polls.

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

**Live updates.** `values-local.yaml` publishes Centrifugo the same way, on http://localhost:8000, and the browser
connects to it there. With the web UI open, the header shows **Live**, and a bid placed from another tab or with
`curl` appears within a moment instead of at the next poll. If Centrifugo can't be reached, the header shows
**Live: polling** and the page polls every 2 seconds as before. Without a LoadBalancer on localhost, also run
`kubectl -n auction port-forward svc/auction-aeron-auction-centrifugo 8000:8000`.

Centrifugo's admin UI, with its live channel list, is at http://localhost:8000. Log in with the admin password:

```bash
kubectl -n auction get secret auction-centrifugo -o jsonpath='{.data.CENTRIFUGO_ADMIN_PASSWORD}' | base64 -d; echo
```

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

Or with [k9s](#dashboards), instead of the last line: go to `:sts`, select `auction-aeron-auction-cluster` and press
`r`, then do the same in `:deploy` for `auction-aeron-auction-client`. In `:pods`, the members are replaced one at a
time.

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
| Centrifugo                   | `ClusterIP`, reached through the Ingress at `/connection` | `LoadBalancer` on `localhost:8000`, Secret `auction-centrifugo` |

## Centrifugo in its own namespace

The chart can run Centrifugo itself (above), or the client can publish to one that runs on its own, installed with
the [official chart](https://github.com/centrifugal/helm-charts) in namespace `centrifugo`. Use your own when several
apps share Centrifugo, when it should be upgraded and scaled apart from the auction release, or on a real cluster.

| | Built in (`centrifugo.enabled: true`) | Own namespace (`values-centrifugo-external.yaml`) |
|---|---|---|
| Installed by | The aeron-auction chart | `centrifugal/centrifugo` 13.7.1, image `v6.10.2` |
| Ports | One Service on `:8000`: WebSocket, server API and admin UI | `centrifugo` on `:8000` for WebSocket only; `centrifugo-internal` (ClusterIP) on `:9000` for the server API, admin UI, health and metrics |
| Client publishes to | `http://<release>-centrifugo:8000/api` | `http://centrifugo-internal.centrifugo.svc.cluster.local:9000/api` |
| Secrets | One, `auction-centrifugo` | One per namespace, both holding the same API key |

Only the WebSocket port is public in the second setup, so the API key is the only thing that guards publishing, and it
never leaves the cluster.

**1. Install Centrifugo.** Secrets belong to a namespace, so the API key goes into both: Centrifugo reads all three keys
from `centrifugo`, and the client reads the API key from `auction-centrifugo`.

```bash
helm repo add centrifugal https://centrifugal.github.io/helm-charts
helm repo update centrifugal

API_KEY="$(openssl rand -hex 32)"
kubectl create namespace centrifugo
kubectl -n centrifugo create secret generic centrifugo \
  --from-literal=CENTRIFUGO_HTTP_API_KEY="$API_KEY" \
  --from-literal=CENTRIFUGO_ADMIN_PASSWORD="$(openssl rand -hex 16)" \
  --from-literal=CENTRIFUGO_ADMIN_SECRET="$(openssl rand -hex 32)"
kubectl create namespace auction --dry-run=client -o yaml | kubectl apply -f -
kubectl -n auction create secret generic auction-centrifugo \
  --from-literal=CENTRIFUGO_HTTP_API_KEY="$API_KEY" --dry-run=client -o yaml | kubectl apply -f -

helm install centrifugo centrifugal/centrifugo --version 13.7.1 -n centrifugo \
  -f infra/k8s/centrifugo/values.yaml \
  -f infra/k8s/centrifugo/values-local.yaml
kubectl -n centrifugo rollout status deployment/centrifugo
```

`values-local.yaml` publishes the `centrifugo` Service on localhost, like the client, so browsers connect to
`ws://localhost:8000/connection/websocket`. Without a LoadBalancer on localhost, run
`kubectl -n centrifugo port-forward svc/centrifugo 8000:8000`.

**2. Point the auction release at it.** Add `values-centrifugo-external.yaml` after the other values files. It turns
off the built-in Centrifugo and sets the server API URL. Use `helm install` for a new release, or `helm upgrade` to
move an existing one; the upgrade removes the built-in Centrifugo.

```bash
helm upgrade --install auction infra/k8s/helm/aeron-auction -n auction \
  -f infra/k8s/helm/aeron-auction/values-local.yaml \
  -f infra/k8s/helm/aeron-auction/values-centrifugo-external.yaml
```

Pass the same `-f` files on every later upgrade, or the release brings its own Centrifugo back.

**3. Check it.** The client logs `live updates go to Centrifugo at http://centrifugo-internal...`, and the web UI shows
**Live**. The admin UI is on the internal port:

```bash
kubectl -n auction logs deploy/auction-aeron-auction-client | grep "live updates"
kubectl -n centrifugo get secret centrifugo -o jsonpath='{.data.CENTRIFUGO_ADMIN_PASSWORD}' | base64 -d; echo
kubectl -n centrifugo port-forward svc/centrifugo-internal 9000:9000   # then open http://localhost:9000
```

**Change the API key** by updating both Secrets with the same new value, then restarting both sides, because env vars
are read at startup: `kubectl -n centrifugo rollout restart deployment/centrifugo` and
`kubectl -n auction rollout restart deployment/auction-aeron-auction-client`. To keep the two in step automatically,
sync both from one Vault path with External Secrets Operator, as in [Secrets with Vault](#secrets-with-vault).

**On a real cluster**, set `service.type: ClusterIP` and turn on the official chart's `ingress` with a host such as
`realtime.example.com`, then set `centrifugo.websocketUrl: wss://realtime.example.com/connection/websocket` in the
auction values. A NetworkPolicy must let the client pods reach `centrifugo-internal:9000` across namespaces.

**Remove.**

```bash
helm upgrade auction infra/k8s/helm/aeron-auction -n auction -f infra/k8s/helm/aeron-auction/values-local.yaml \
  --set centrifugo.enabled=false   # or drop the flag to go back to the built-in Centrifugo
helm uninstall centrifugo -n centrifugo
kubectl delete namespace centrifugo
kubectl -n auction delete secret auction-centrifugo
```

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

[Headlamp](https://headlamp.dev) and [k9s](https://k9scli.io) are lighter: they use your kubeconfig, so nothing is
installed in the cluster and no token is needed.

**k9s**, a terminal UI and the quickest way to watch the cluster:

```bash
brew install k9s
k9s -n auction
```

Type `:` and a resource to switch views, such as `:pods`, `:sts`, `:deploy`, `:svc`, `:pvc`, `:secrets` or `:events`.
`/` filters the list, `?` shows every key and `:q` quits.

| Key       | On                    | Does                                                                     |
|-----------|-----------------------|--------------------------------------------------------------------------|
| `l`       | Pod                   | Follows its logs                                                         |
| `d`, `y`  | Any resource          | Describes it, or shows its YAML                                          |
| `s`       | Pod                   | Opens a shell in it                                                      |
| `shift-f` | Pod                   | Port-forwards a port, such as `9100` on a member or `8080` on a client. `:pf` lists them |
| `ctrl-d`  | Any resource          | Deletes it, after asking                                                 |
| `r`       | StatefulSet, Deployment | Rolls its pods, like `kubectl rollout restart`                         |
| `x`       | Secret                | Shows the decoded values, such as `auction-client-secrets`               |

To try [failover](#deploy-locally) from k9s: in `:pods`, port-forward `9100` on each member with `shift-f`, run
`curl -s localhost:<port>/actuator/prometheus | grep '^aeron_cluster_role'` to find the leader (`2`), then select the
leader's pod and press `ctrl-d`. The list shows the pod restart and turn `1/1` again once it has rejoined.

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
| Centrifugo's secrets                              | The Secret named by `centrifugo.existingSecret`                        | `CENTRIFUGO_HTTP_API_KEY`, also read by the client pods           |

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

Create Centrifugo's Secret first, as in step 3 of [Deploy locally](#deploy-locally), or sync it from Vault like the
client's, and pass `--set centrifugo.existingSecret=<its name>`. For production, prefer running Centrifugo in
[its own namespace](#centrifugo-in-its-own-namespace). With the Ingress on, browsers reach Centrifugo on the
same host at `/connection/websocket`. Centrifugo accepts any origin by default (`centrifugo.allowedOrigins: ["*"]`),
since browsers can only subscribe; list your hosts there to narrow it, or set `[]` to use the Ingress hosts. WebSocket connections stay open, so raise the Ingress controller's idle
timeout, such as `nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"` for ingress-nginx. Centrifugo keeps its
history in memory, so the chart runs one replica; more need Redis or NATS as its broker.

With the default `podAntiAffinity: hard` the cluster needs at least 3 schedulable nodes. Pod-to-pod UDP must be
allowed between the members and clients (ports 9002-9006, 8010 and 9007), including by any NetworkPolicy. Set
`clusterDomain` if your cluster's DNS suffix isn't `cluster.local`.
