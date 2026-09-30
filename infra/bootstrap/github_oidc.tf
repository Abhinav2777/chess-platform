# CI pushes to ECR with no stored AWS credentials. Each workflow run asks GitHub for a
# short-lived OIDC token that says which repository, branch and event it is; AWS STS trades it
# for one-hour credentials — if, and only if, the claims match the role's trust policy.
#
# The alternative, an IAM user's access key in a GitHub secret, is a credential that never
# expires, works from anywhere, and leaks with the first misconfigured workflow log.

# One per account per issuer URL. No thumbprint: AWS verifies GitHub's certificate against its
# own trusted CAs for this issuer, so a pinned thumbprint would only break on rotation.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "ci_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Exact match on repository AND branch: a pull request, a fork, another branch or another
    # repository in the same account gets a token whose sub differs, and is refused. The most
    # common mistake here is StringLike with "repo:owner/*" — every repository of the owner.
    #
    # The sub is GitHub's immutable form, with numeric owner and repository IDs. A name-only sub
    # ("repo:owner/name:…") would be matched by a *new* repository created under the same name
    # after this one is deleted or renamed; IDs are never reused. The first CI run used the
    # name-only form and was refused — CloudTrail showed the sub GitHub actually sent.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${var.github_sub_prefix}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "ci_ecr_push" {
  name               = "chess-platform-ci-ecr-push"
  description        = "GitHub Actions on main: push images to the chess-platform ECR repository. Nothing else."
  assume_role_policy = data.aws_iam_policy_document.ci_trust.json
}

data "aws_iam_policy_document" "ci_ecr_push" {
  # Registry-wide by design: the login token is not scoped to a repository.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # Push, plus the reads a push performs (which layers already exist). One repository only.
  statement {
    sid = "PushToThisRepository"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [aws_ecr_repository.app.arn]
  }
}

resource "aws_iam_role_policy" "ci_ecr_push" {
  name   = "ecr-push"
  role   = aws_iam_role.ci_ecr_push.id
  policy = data.aws_iam_policy_document.ci_ecr_push.json
}
