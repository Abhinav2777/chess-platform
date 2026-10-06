#!/usr/bin/env bash
# Phase 9.4: run games.js as a one-off ECS task INSIDE the VPC against the deployed stack, then
# read the result from CloudWatch Logs and the server side from CloudWatch metrics.
#   loadtest/aws-loadtest.sh <games> [play_seconds] [ramp_seconds]
# Needs: infra/app applied with -var loadgen_enabled=true -var relaxed_auth_rate_limits=true.
# Env: THINK_MIN_MS / THINK_MAX_MS (defaults 800 / 2000, as the kind baseline).
set -euo pipefail
cd "$(dirname "$0")"
GAMES=${1:?usage: aws-loadtest.sh <games> [play_seconds] [ramp_seconds]}
PLAY=${2:-120}; RAMP=${3:-30}
THINK_MIN=${THINK_MIN_MS:-800}; THINK_MAX=${THINK_MAX_MS:-2000}
OUT=results; mkdir -p "$OUT"; tag="$OUT/aws-$(date +%Y%m%d-%H%M%S)-${GAMES}g"

lg=$(cd ../infra/app && terraform output -json loadgen)
[[ "$lg" != "null" ]] || { echo "loadgen not enabled: apply with -var loadgen_enabled=true" >&2; exit 1; }
get() { jq -r ".$1" <<< "$lg"; }
CLUSTER=$(get cluster); TASKDEF=$(get task_definition); SG=$(get security_group); SUBNETS=$(get subnets)
LOGS=$(get log_group); ALB=$(get alb_arn_suffix); TG=$(get tg_arn_suffix); DB=$(get db_identifier)

# The ALB's private address (see infra/app/loadgen.tf for why not its DNS name).
ALB_IP=$(aws ec2 describe-network-interfaces \
  --filters "Name=description,Values=ELB ${ALB}" "Name=status,Values=in-use" \
  --query 'NetworkInterfaces[0].PrivateIpAddress' --output text)
[[ "$ALB_IP" =~ ^10\. ]] || { echo "could not find the ALB's private address (got: $ALB_IP)" >&2; exit 1; }

echo "games=$GAMES sockets=$((GAMES * 2)) play=${PLAY}s ramp=${RAMP}s think=${THINK_MIN}-${THINK_MAX}ms target=http://$ALB_IP" | tee "$tag.txt"
overrides=$(jq -nc --arg u "http://$ALB_IP" --arg g "$GAMES" --arg p "$PLAY" --arg r "$RAMP" --arg a "$THINK_MIN" --arg b "$THINK_MAX" \
  '{containerOverrides:[{name:"k6",environment:[{name:"BASE_URL",value:$u},{name:"GAMES",value:$g},{name:"PLAY_SECONDS",value:$p},{name:"RAMP_SECONDS",value:$r},{name:"THINK_MIN_MS",value:$a},{name:"THINK_MAX_MS",value:$b}]}]}')

start=$(date -u +%FT%TZ)
task=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$TASKDEF" --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=ENABLED}" \
  --overrides "$overrides" --query 'tasks[0].taskArn' --output text)
echo "k6 task ${task##*/} started $start; waiting…"
aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$task" || \
  aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$task"   # waiter caps at 10 min; twice covers it
end=$(date -u +%FT%TZ)

# The whole stream (paginated by the CLI) for the record; the summary and exit code fetched by
# pattern, because a noisy run (thousands of warning lines) pushes them past the first page — the
# first session lost its summaries that way.
aws logs filter-log-events --log-group-name "$LOGS" --log-stream-names "k6/k6/${task##*/}" \
  --query 'events[].message' --output text | tr '\t' '\n' > "$tag.k6.log" || true
aws logs filter-log-events --log-group-name "$LOGS" --log-stream-names "k6/k6/${task##*/}" \
  --filter-pattern '"K6_SUMMARY_JSON"' --query 'events[-1].message' --output text \
  | grep -m1 '^K6_SUMMARY_JSON ' | sed 's/^K6_SUMMARY_JSON //' > "$tag.k6.json" || true
# The CLI applies --query per page, and an empty page prints "None": keep only the real line.
exit_line=$(aws logs filter-log-events --log-group-name "$LOGS" --log-stream-names "k6/k6/${task##*/}" \
  --filter-pattern '"K6_EXIT"' --query 'events[-1].message' --output text | grep -m1 '^K6_EXIT' || true)
# 99 = k6 thresholds failed (a result, not an error): keep going and report it.
echo "${exit_line:-K6_EXIT unknown} (99 = thresholds failed)" | tee -a "$tag.txt"

# Server side from CloudWatch (1-minute resolution — coarser than the kind scraper, stated as such).
metric() {  # namespace name stat dims...
  local ns=$1 name=$2 stat=$3; shift 3
  aws cloudwatch get-metric-statistics --namespace "$ns" --metric-name "$name" --dimensions "$@" \
    --start-time "$start" --end-time "$end" --period 60 --statistics "$stat" \
    --query "Datapoints[].$stat" --output text | tr '\t' '\n' | sort -n | tail -1
}
{
  echo "server (CloudWatch, max of 1-minute ${start} .. ${end}):"
  echo "  ECS api CPU %        $(metric AWS/ECS CPUUtilization Maximum Name=ClusterName,Value=$CLUSTER Name=ServiceName,Value=api)"
  echo "  ECS api memory %     $(metric AWS/ECS MemoryUtilization Maximum Name=ClusterName,Value=$CLUSTER Name=ServiceName,Value=api)"
  echo "  ECS worker CPU %     $(metric AWS/ECS CPUUtilization Maximum Name=ClusterName,Value=$CLUSTER Name=ServiceName,Value=worker)"
  echo "  RDS CPU %            $(metric AWS/RDS CPUUtilization Maximum Name=DBInstanceIdentifier,Value=$DB)"
  echo "  RDS connections      $(metric AWS/RDS DatabaseConnections Maximum Name=DBInstanceIdentifier,Value=$DB)"
  echo "  ALB requests/min     $(metric AWS/ApplicationELB RequestCount Sum Name=LoadBalancer,Value=$ALB)"
  echo "  ALB 5xx (target)     $(metric AWS/ApplicationELB HTTPCode_Target_5XX_Count Sum Name=LoadBalancer,Value=$ALB)"
  echo "  healthy targets min  $(aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name HealthyHostCount \
      --dimensions Name=LoadBalancer,Value=$ALB Name=TargetGroup,Value=$TG --start-time "$start" --end-time "$end" \
      --period 60 --statistics Minimum --query 'Datapoints[].Minimum' --output text | tr '\t' '\n' | sort -n | head -1)"
} | tee -a "$tag.txt"
if [[ -s "$tag.k6.json" ]]; then
  jq -r '.metrics | "k6 games verified \(.games_verified.count // 0), inconsistent \(.games_inconsistent.count // 0), clock anomalies \(.clock_anomalies.count // 0), rejected \(.moves_rejected.count // 0), abnormal closes \(.ws_closes_abnormal.count // 0)",
    "k6 moves/s \(.moves_acked.rate | .*10|round/10)  ack p50 \(.move_ack_ms.med) p95 \(.move_ack_ms["p(95)"]) p99 \(.move_ack_ms["p(99)"] // "n/a") max \(.move_ack_ms.max) ms"' "$tag.k6.json" | tee -a "$tag.txt"
fi
echo "files: $tag.*"
