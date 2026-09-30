# Two roles per task, and they answer different questions:
#   execution role — what ECS itself needs to START the task: pull the image, read the secrets
#                    it injects, write logs. The application never holds these credentials.
#   task role      — what the APPLICATION may do once running. The api and migrate tasks have
#                    none at all: they call no AWS API. The worker may use two queues.
# Mixing them is the common mistake: the app ends up able to read every secret and pull any
# image, because that is what starting it required.

data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    # Confused-deputy guard: only ECS acting for THIS account may assume these roles.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

data "aws_ecr_repository" "app" {
  name = "chess-platform" # created by infra/bootstrap
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

# Written out rather than AmazonECSTaskExecutionRolePolicy, which allows pulling from every
# repository and writing to every log group in the account.
data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # not scopable
  }
  statement {
    sid       = "PullThisImage"
    actions   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
    resources = [data.aws_ecr_repository.app.arn]
  }
  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [for g in aws_cloudwatch_log_group.task : "${g.arn}:*"]
  }
  statement {
    sid       = "InjectSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_db_instance.main.master_user_secret[0].secret_arn, aws_secretsmanager_secret.jwt.arn]
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "start-tasks"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

resource "aws_iam_role" "worker_task" {
  name               = "${var.name}-worker-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

data "aws_iam_policy_document" "worker_task" {
  # The relay sends; the rating consumer receives, deletes, extends visibility (ADR-020).
  statement {
    sid = "GameEvents"
    actions = [
      "sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility", "sqs:GetQueueUrl", "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.game_events.arn]
  }
  # SqsQueues resolves the DLQ's URL at startup (create-queues: false). Nothing reads it.
  statement {
    sid       = "ResolveDeadLetterQueue"
    actions   = ["sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.game_events_dlq.arn]
  }
}

resource "aws_iam_role_policy" "worker_task" {
  name   = "game-events"
  role   = aws_iam_role.worker_task.id
  policy = data.aws_iam_policy_document.worker_task.json
}
