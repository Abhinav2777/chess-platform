variable "region" {
  description = "Everything in this project runs in one region (ADR-023)."
  type        = string
  default     = "us-east-1"
}

variable "github_repository" {
  description = "owner/name of the only repository whose CI may push images. Case-sensitive: it is compared with the OIDC token's sub claim."
  type        = string
  default     = "Abhinav2777/chess-platform"
}

variable "budget_name" {
  description = "Name of the monthly cost budget. Set it to an existing budget's name to adopt that budget (terraform import) instead of creating a second."
  type        = string
  default     = "chess-platform-monthly"
}

variable "budget_limit_usd" {
  description = "Monthly cost budget. DEPLOYMENT.md rule 1: in place before anything else is provisioned."
  type        = number
  default     = 20
}

variable "budget_alert_emails" {
  description = "Who is emailed when spend crosses a threshold. Personal data: set in terraform.tfvars (gitignored), not here."
  type        = list(string)

  validation {
    condition     = length(var.budget_alert_emails) > 0
    error_message = "A budget nobody is told about is not an alarm. Give at least one address."
  }
}

variable "ecr_images_to_keep" {
  description = "Tagged images kept in ECR; older ones expire. Each is a deployable commit."
  type        = number
  default     = 3
}
