#!/usr/bin/env bash
# Phase 8.3: k6 games across `kubectl rollout restart deployment/api` (ADR-024).
#   GAMES=40 PLAY_SECONDS=180 ROLLOUT_AT=60 loadtest/rolling-deploy.sh
#   ROLLOUT_AT=none                        -> control run, no rollout
#   DISRUPTION=crash                       -> instead of a rollout, SIGKILL one api JVM from the
#                                             node (no SIGTERM, no drain: a real crash). Expect
#                                             abnormal closes — and still zero lost games.
# Not `kubectl delete pod --grace-period=0 --force`: that removes the API object at once, but the
# kubelet still sends SIGTERM first — the drain runs, and it is a graceful shutdown (8.3 finding).
# Not `kill -9 1` inside the container: PID 1 ignores signals it has no handler for.
# Needs the kind cluster from k8s/cluster-up.sh + k8s/deploy.sh.
set -euo pipefail
cd "$(dirname "$0")"
GAMES=${GAMES:-40}; PLAY_SECONDS=${PLAY_SECONDS:-180}; ROLLOUT_AT=${ROLLOUT_AT:-60}
DISRUPTION=${DISRUPTION:-rollout}
OUT=${OUT:-results}; mkdir -p "$OUT"; stamp=$(date +%Y%m%d-%H%M%S)

replicas() { kubectl -n chess get deployment api -o jsonpath='{.status.readyReplicas}'; }
echo "$(date +%T) api pods ready at start: $(replicas)   (the HPA may change this — recorded, not assumed)"

GAMES=$GAMES PLAY_SECONDS=$PLAY_SECONDS k6 run --quiet \
  --summary-export "$OUT/rolling-deploy-$stamp.json" games.js > "$OUT/rolling-deploy-$stamp.log" 2>&1 &
K6=$!

# The load generator's own CPU and peak memory, sampled from /proc, so a report can show k6 was
# not the bottleneck (docs/perf/README.md). CPU = (utime + stime) / wall time, in cores.
( hz=$(getconf CLK_TCK); t0=$(date +%s.%N); peak=0; ticks=0
  while [[ -r /proc/$K6/stat ]]; do
    read -r -a st < /proc/$K6/stat || break
    ticks=$(( st[13] + st[14] ))
    rss=$(awk '/VmRSS/ {print $2}' /proc/$K6/status 2>/dev/null || echo 0); (( rss > peak )) && peak=$rss
    sleep 1
  done
  awk -v t="$ticks" -v hz="$hz" -v t0="$t0" -v now="$(date +%s.%N)" -v p="$peak" -v n="$(nproc)" \
    'BEGIN { w = now - t0; printf "k6 load generator: %.2f cores average of %d (%.1f CPU-s over %.0f s), peak RSS %.0f MiB\n", (t/hz)/w, n, t/hz, w, p/1024 }' \
    > "$OUT/rolling-deploy-$stamp.k6-resources" ) &
MON=$!

if [[ "$ROLLOUT_AT" != none ]]; then
  sleep "$ROLLOUT_AT"
  start=$(date +%s)
  if [[ "$DISRUPTION" == crash ]]; then
    node=chess-control-plane
    cid=$(docker exec "$node" crictl ps --name '^api$' -q | head -1)
    pid=$(docker exec "$node" crictl inspect -o go-template --template '{{.info.pid}}' "$cid")
    echo "$(date +%T) SIGKILL to api container ${cid:0:12} (host pid $pid)"
    docker exec "$node" kill -9 "$pid"
    sleep 2
    kubectl -n chess wait --for=condition=ready pod -l app.kubernetes.io/name=api --timeout=300s >/dev/null
    echo "$(date +%T) all api pods ready again in $(( $(date +%s) - start )) s (container restarted in place)"
  else
    echo "$(date +%T) kubectl rollout restart deployment/api"
    kubectl -n chess rollout restart deployment/api >/dev/null
    kubectl -n chess rollout status deployment/api --timeout=300s >/dev/null
    echo "$(date +%T) rollout complete in $(( $(date +%s) - start )) s"
  fi
fi

status=0; wait $K6 || status=$?
echo "$(date +%T) api pods ready at end: $(replicas)"
wait $MON 2>/dev/null || true; cat "$OUT/rolling-deploy-$stamp.k6-resources"
echo "k6 exit: $status (thresholds $( [[ $status == 0 ]] && echo passed || echo FAILED ))"
echo "log: $OUT/rolling-deploy-$stamp.log   summary: $OUT/rolling-deploy-$stamp.json"
exit $status
