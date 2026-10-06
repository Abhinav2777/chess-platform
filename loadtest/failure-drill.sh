#!/usr/bin/env bash
# Failure drill on kind (Phase 10.2): live games, one dependency fails, it comes back — and a
# record of what players saw, what the server did, and whether anything was lost.
#
#   loadtest/failure-drill.sh <scenario> [down-seconds] [games]
#
#   pg-crash     PostgreSQL immediate shutdown (SIGQUIT to the postmaster): connections refused,
#                kubelet restarts the container, WAL recovery. down-seconds unused — it restarts
#                on its own; the outage is measured.
#   pg-hang      every PostgreSQL process, postmaster included, SIGSTOPped from the kind node for
#                down-seconds: nothing refuses, nothing answers (a gray failure).
#   queue-hang   ElasticMQ SIGSTOPped for down-seconds — SQS unreachable. Frozen, not deleted:
#                ElasticMQ is in-memory and the app creates its queues only at startup, so a
#                deleted pod would come back without queues, a failure SQS does not have.
#   worker-down  the worker (relay + rating consumer) scaled to 0 for down-seconds.
#   none         control run.
#
# Never scales PostgreSQL to zero: its data is an emptyDir, which a container restart keeps and a
# pod deletion destroys.
#
# Output: loadtest/results/drill-<time>-<scenario>.{k6.log,k6.json,timeline.tsv,api.log,summary.txt}
set -euo pipefail
cd "$(dirname "$0")"

SCENARIO=${1:?usage: failure-drill.sh <pg-crash|pg-hang|queue-hang|worker-down|none> [down-seconds] [games]}
DOWN=${2:-30}
GAMES=${3:-20}
NS=chess
FAULT_AT=60                      # seconds after k6 starts: games are under way
# Every game resigns at its deadline (RESIGN_AT_END), producing a rating event.
#   PostgreSQL drills: play on well past recovery — the subject is gameplay through the outage.
#   Queue / worker drills: games end inside the outage — the subject is the rating backlog.
case "$SCENARIO" in
  queue-hang|worker-down) PLAY=$((FAULT_AT + 10)) ;;
  *)                      PLAY=$((FAULT_AT + DOWN + 120)) ;;
esac
SAMPLE_UNTIL=$((FAULT_AT + DOWN + 60))  # keep sampling after k6 ends: the catch-up
BASE_URL=${BASE_URL:-http://localhost}
tag="results/drill-$(date +%Y%m%d-%H%M%S)-$SCENARIO"
mkdir -p results

k() { kubectl -n "$NS" "$@"; }
# Bounded: during pg-hang an exec'd psql would wait forever.
sql() { timeout 3 kubectl -n "$NS" exec deploy/postgres -- sh -c "psql -U \"\$POSTGRES_USER\" -d chess -tAc \"$1\"" 2>/dev/null || echo "-"; }
api_ready() { k get endpointslices -l kubernetes.io/service-name=api \
  -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' 2>/dev/null | grep -c true || true; }
status() { curl -s -o /dev/null -m 3 -w '%{http_code}' "$@" || echo "000"; }

inject() {
  case "$SCENARIO" in
    pg-crash)    k exec deploy/postgres -- sh -c 'kill -QUIT 1' ;;
    # From the kind node, not inside the container: there the postmaster is PID 1, and Linux
    # ignores SIGSTOP sent to a namespace's init from inside it — an in-container pkill froze only
    # the backends, and the postmaster went on accepting connections (found on the first run).
    pg-hang)     docker exec "${CLUSTER:-chess}-control-plane" pkill -STOP -x postgres ;;
    queue-hang)  k exec deploy/elasticmq -- sh -c 'kill -STOP 14 2>/dev/null || pkill -STOP -f elasticmq-native-server' ;;
    worker-down) k scale deploy/worker --replicas=0 ;;
    none)        ;;
    *) echo "unknown scenario $SCENARIO" >&2; exit 2 ;;
  esac
}
restore() {
  case "$SCENARIO" in
    pg-hang)     docker exec "${CLUSTER:-chess}-control-plane" pkill -CONT -x postgres ;;
    queue-hang)  k exec deploy/elasticmq -- sh -c 'kill -CONT 14 2>/dev/null || pkill -CONT -f elasticmq-native-server' ;;
    worker-down) k scale deploy/worker --replicas=1 ;;
    *)           ;;
  esac
}

echo "scenario=$SCENARIO down=${DOWN}s games=$GAMES fault_at=${FAULT_AT}s play=${PLAY}s" | tee "$tag.summary.txt"
start=$(date +%s)
since=$(date -u +%FT%TZ)

GAMES=$GAMES PLAY_SECONDS=$PLAY RAMP_SECONDS=10 RESIGN_AT_END=1 BASE_URL=$BASE_URL \
  k6 run --quiet --summary-export "$tag.k6.json" games.js > "$tag.k6.log" 2>&1 &
k6pid=$!

# Timeline every 2 s until k6 ends: phase, ready API endpoints, what a new visitor gets (SPA and
# API), outbox backlog, ratings processed, restarts.
phase=before
printf 't\tphase\tapi_ready\tspa\tapi\toutbox_unpublished\tprocessed_events\tpg_restarts\n' > "$tag.timeline.tsv"
t=0
while kill -0 "$k6pid" 2>/dev/null || [[ $t -lt $SAMPLE_UNTIL ]]; do
  t=$(( $(date +%s) - start ))
  if [[ $phase == before && $t -ge $FAULT_AT ]]; then
    inject; phase=down; echo "t=${t}s fault injected" | tee -a "$tag.summary.txt"
  elif [[ $phase == down && $t -ge $((FAULT_AT + DOWN)) ]]; then
    restore; phase=after; echo "t=${t}s restored" | tee -a "$tag.summary.txt"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$t" "$phase" "$(api_ready)" \
    "$(status "$BASE_URL/")" "$(status "$BASE_URL/api/users/me")" \
    "$(sql 'select count(*) from outbox where published_at is null')" \
    "$(sql 'select count(*) from processed_events')" \
    "$(k get pods -l app.kubernetes.io/name=postgres -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}' 2>/dev/null)" \
    >> "$tag.timeline.tsv"
  sleep 2
done
wait "$k6pid" || true
[[ $phase == down ]] && restore   # never leave a dependency broken

# Let the backlog drain, then the final state.
for _ in $(seq 1 30); do
  [[ "$(sql 'select count(*) from outbox where published_at is null')" == "0" ]] && break
  sleep 2
done
k logs -l app.kubernetes.io/name=api --since-time="$since" --tail=-1 --prefix > "$tag.api.log" 2>/dev/null || true
k logs -l app.kubernetes.io/name=worker --since-time="$since" --tail=-1 --prefix >> "$tag.api.log" 2>/dev/null || true

{
  echo "--- k6"
  grep -E "✓|✗|games_|moves_|ws_closes|ws_reconnect|ws_auth|requests_shed|clock_anomalies|move_ack_ms" "$tag.k6.log" || true
  echo "--- final: outbox unpublished $(sql 'select count(*) from outbox where published_at is null'), processed $(sql 'select count(*) from processed_events'), finished games $(sql "select count(*) from games where status <> 'ACTIVE'")"
  echo "--- server WARN/ERROR (api + worker), by message"
  python3 - "$tag.api.log" <<'PY'
import json, re, sys, collections
seen = collections.Counter()
for line in open(sys.argv[1], errors="replace"):
    start = line.find("{")
    try:
        event = json.loads(line[start:])
    except ValueError:
        continue
    if event.get("level") in ("WARN", "ERROR"):
        message = re.sub(r"[0-9a-f]{8}-[0-9a-f-]{27}", "<id>", event.get("message", ""))
        seen[(event["level"], re.sub(r"\d+ms", "Nms", message)[:110])] += 1
for (level, message), n in seen.most_common(15):
    print(f"{n:6d} {level:5s} {message}")
PY
} | tee -a "$tag.summary.txt"
echo "results: $tag.*"
