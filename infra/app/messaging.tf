# The game-events queue and its dead-letter queue — exactly what SqsQueues creates locally
# (ElasticMQ), because in AWS the app is forbidden to create them (create-queues: false,
# queue-not-found-strategy: fail): an auto-created queue would have no redrive policy.

resource "aws_sqs_queue" "game_events_dlq" {
  name                      = "game-events-dlq"
  message_retention_seconds = 1209600 # 14 days, the maximum: time to notice and replay
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "game_events" {
  name                       = "game-events"
  visibility_timeout_seconds = 30 # chess.messaging.visibility-timeout
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20 # long polling: fewer empty receives, fewer requests billed
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.game_events_dlq.arn
    maxReceiveCount     = 3 # chess.messaging.max-receive-count (ADR-008)
  })
}

# Only game-events may dead-letter into the DLQ.
resource "aws_sqs_queue_redrive_allow_policy" "game_events_dlq" {
  queue_url = aws_sqs_queue.game_events_dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.game_events.arn]
  })
}
