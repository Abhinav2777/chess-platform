#!/usr/bin/env bash
# Phase 9.2 baseline on kind: live games at fixed connection counts, API replicas pinned.
#   loadtest/baseline.sh 50 250 500        # games per level (2 sockets per game)
# Each level: server snapshot -> sampler -> k6 (games.js, RAMP_SECONDS stagger) -> snapshot ->
# report (server-side percentiles from histogram deltas, pool waits, CPU) + k6 summary.
# Needs the kind cluster (k8s/cluster-up.sh + k8s/deploy.sh). Restore the HPA afterwards with
# k8s/deploy.sh <tag>.
set -euo pipefail
cd "$(dirname "$0")"
LEVELS=("$@"); [[ ${#LEVELS[@]} -gt 0 ]] || LEVELS=(50 250 500)
PLAY_SECONDS=${PLAY_SECONDS:-120}; RAMP_SECONDS=${RAMP_SECONDS:-30}; API_REPLICAS=${API_REPLICAS:-2}
OUT=results; mkdir -p "$OUT"; stamp=$(date +%Y%m%d-%H%M%S)

# The system under test must not change mid-run: no autoscaling, a fixed replica count.
kubectl -n chess delete hpa api --ignore-not-found >/dev/null
kubectl -n chess scale deployment/api --replicas="$API_REPLICAS" >/dev/null
kubectl -n chess rollout status deployment/api --timeout=300s >/dev/null
echo "api replicas fixed at $API_REPLICAS (HPA removed for the baseline)"

for games in "${LEVELS[@]}"; do
  tag="$OUT/baseline-$stamp-${games}g"
  echo "=== $games games ($((games * 2)) sockets), ramp ${RAMP_SECONDS}s, play ${PLAY_SECONDS}s, think ${THINK_MIN_MS:-800}-${THINK_MAX_MS:-2000} ms"
  ./server-metrics.py snapshot "$tag.before.json"
  ./server-metrics.py sample $((PLAY_SECONDS + RAMP_SECONDS + 30)) "$tag.samples.jsonl" &
  SAMPLER=$!
  GAMES=$games PLAY_SECONDS=$PLAY_SECONDS RAMP_SECONDS=$RAMP_SECONDS \
    THINK_MIN_MS=${THINK_MIN_MS:-800} THINK_MAX_MS=${THINK_MAX_MS:-2000} \
    timeout -s INT --kill-after=30 $((PLAY_SECONDS + RAMP_SECONDS + 150)) k6 run --quiet \
    --summary-export "$tag.k6.json" games.js > "$tag.k6.log" 2>&1 &
  K6=$!
  # $! is `timeout`; measure the k6 process under it.
  sleep 1; K6PROC=$(pgrep -P "$K6" -x k6 || echo "$K6")
  ( hz=$(getconf CLK_TCK); t0=$(date +%s.%N); peak=0; ticks=0
    while [[ -r /proc/$K6PROC/stat ]]; do
      read -r -a st < /proc/$K6PROC/stat || break; ticks=$(( st[13] + st[14] ))
      rss=$(awk '/VmRSS/ {print $2}' /proc/$K6PROC/status 2>/dev/null || echo 0); (( rss > peak )) && peak=$rss
      sleep 1
    done
    awk -v t="$ticks" -v hz="$hz" -v t0="$t0" -v now="$(date +%s.%N)" -v p="$peak" -v n="$(nproc)" \
      'BEGIN { w = now - t0; printf "k6: %.2f cores average of %d, peak RSS %.0f MiB\n", (t/hz)/w, n, p/1024 }' ) > "$tag.k6-resources" &
  MON=$!
  status=0; wait $K6 || status=$?
  ./server-metrics.py snapshot "$tag.after.json"
  kill $SAMPLER 2>/dev/null || true; wait $MON 2>/dev/null || true
  {
    echo "games=$games sockets=$((games * 2)) api_replicas=$API_REPLICAS k6_exit=$status"
    cat "$tag.k6-resources"
    grep -E "^\s+(games_|moves_|clock_|ws_closes|move_ack|ws_connecting)" "$tag.k6.log" | sed 's/^\s*/k6 /'
    ./server-metrics.py report "$tag.before.json" "$tag.after.json" "$tag.samples.jsonl"
  } | tee "$tag.summary.txt"
done
