output "state_bucket" {
  description = "Remote state bucket, for backend blocks (this stack's, after migration, and infra/app's)."
  value       = aws_s3_bucket.state.bucket
}

output "ecr_repository_url" {
  description = "Where CI pushes and ECS pulls: <account>.dkr.ecr.<region>.amazonaws.com/chess-platform."
  value       = aws_ecr_repository.app.repository_url
}

output "ci_role_arn" {
  description = "Set as the GitHub Actions variable AWS_ECR_PUSH_ROLE_ARN (not a secret: useless without a matching OIDC token)."
  value       = aws_iam_role.ci_ecr_push.arn
}
