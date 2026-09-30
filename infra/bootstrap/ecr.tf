# The registry ECS pulls from (Phase 7). GHCR stays the public registry (ADR-022); ECR is the
# private one inside the account: pulls are authorised by the task's IAM execution role, not a
# token, and do not depend on a third party's availability or rate limits at deploy time.

resource "aws_ecr_repository" "app" {
  name = "chess-platform"

  # CI pushes only the full commit SHA to ECR. Immutable means a SHA can never be re-pointed
  # at different bytes: what was tested and scanned is what runs.
  image_tag_mutability = "IMMUTABLE"

  # Basic scanning is free. The gate is still Trivy in CI, before the push; this re-scan
  # catches CVEs published after an image was pushed.
  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Storage is $0.10/GB-month; an image is ~150 MB compressed. Unbounded, that grows with every
# merge to main. Keep the newest few — each a deployable commit — and drop the rest.
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Untagged manifests (interrupted pushes) after a day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        # "any" must be the lowest-priority rule.
        rulePriority = 2
        description  = "Keep the newest ${var.ecr_images_to_keep} images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_images_to_keep
        }
        action = { type = "expire" }
      },
    ]
  })
}
