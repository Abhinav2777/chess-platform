terraform {
  # 1.11: write-only arguments (secret_string_wo) and S3-native locking.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }

  # State in the bootstrap stack's bucket (DEPLOYMENT.md). Bucket given at init:
  #   terraform init -backend-config="bucket=chess-platform-tfstate-<account-id>"
  backend "s3" {
    key          = "app/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "chess-platform"
      Stack     = "app"
      ManagedBy = "terraform"
      # This stack is applied, measured and destroyed (DEPLOYMENT.md rule 2). Anything found
      # in Cost Explorer with this tag after a session is a leftover.
      Lifetime = "ephemeral"
    }
  }
}

data "aws_caller_identity" "current" {}
