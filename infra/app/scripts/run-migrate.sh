#!/usr/bin/env bash
# Runs the one-off migrate task (ADR-021) and fails unless it exits 0.
# Called by terraform_data.migrate (ecs.tf) with its configuration in the environment.
set -euo pipefail

: "${CLUSTER:?}" "${TASK_DEFINITION:?}" "${SUBNETS:?}" "${SECURITY_GROUP:?}" "${LOG_GROUP:?}"

echo "migrate: starting ${TASK_DEFINITION##*/}"
task_arn=$(aws ecs run-task \
  --cluster "$CLUSTER" \
  --task-definition "$TASK_DEFINITION" \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SECURITY_GROUP],assignPublicIp=ENABLED}" \
  --query 'tasks[0].taskArn' --output text)

if [[ -z "$task_arn" || "$task_arn" == "None" ]]; then
  echo "migrate: run-task returned no task" >&2
  exit 1
fi

echo "migrate: waiting for ${task_arn##*/} to stop"
aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$task_arn"

read -r exit_code stop_reason < <(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$task_arn" \
  --query 'tasks[0].[containers[0].exitCode, stoppedReason]' --output text)

if [[ "$exit_code" != "0" ]]; then
  echo "migrate: FAILED (exit code: $exit_code, reason: $stop_reason)" >&2
  echo "migrate: logs: aws logs tail $LOG_GROUP --since 30m" >&2
  exit 1
fi
echo "migrate: done (exit 0)"
