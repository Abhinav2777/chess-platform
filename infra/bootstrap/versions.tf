terraform {
  # 1.11: S3-native state locking (use_lockfile) is GA. DynamoDB locking is deprecated.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
  }

  # No backend block on the very first apply: the bucket it would name does not exist yet.
  # After that apply, backend.tf is added and the state migrated into the bucket this stack
  # created (DEPLOYMENT.md, "Bootstrap"). See backend.tf.
}

provider "aws" {
  region = var.region

  # On every resource that supports tags: a stranger's Cost Explorer (or ours, months later)
  # can answer "what is this and can I delete it" without reading the code.
  default_tags {
    tags = {
      Project   = "chess-platform"
      Stack     = "bootstrap"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
