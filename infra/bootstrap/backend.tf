# Added after the first apply, which created the bucket this names; the local state was then
# migrated in with `terraform init -migrate-state` (DEPLOYMENT.md, "Bootstrap"). From then on
# this stack's own state lives in the bucket it manages — safe, because the bucket has
# prevent_destroy and versioning.
#
# The bucket is left out on purpose ("partial configuration") and given at init time:
#   terraform init -backend-config="bucket=chess-platform-tfstate-$(aws sts get-caller-identity --query Account --output text)"
# It contains the account ID, which need not be in a public repository, and a fork's account
# differs anyway.
terraform {
  backend "s3" {
    key          = "bootstrap/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true # S3-native lock (a .tflock object beside the state); no DynamoDB
  }
}
