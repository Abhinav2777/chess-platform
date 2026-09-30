variable "region" {
  type    = string
  default = "us-east-1"
}

variable "name" {
  description = "Prefix for resource names."
  type        = string
  default     = "chess-platform"
}

variable "vpc_cidr" {
  description = "The VPC range. COUPLED to server.tomcat.remoteip.internal-proxies in application-aws.yml (10.0.x.x): change both or neither."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = startswith(var.vpc_cidr, "10.0.")
    error_message = "application-aws.yml trusts forwarded headers only from 10.0.x.x; change it together with this."
  }
}

variable "allowed_ingress_cidrs" {
  description = "Who may reach the ALB (ADR-023: the deployment is plain HTTP, so it is not public). Your address as a /32: curl -s https://checkip.amazonaws.com. Set in terraform.tfvars."
  type        = list(string)

  validation {
    condition     = length(var.allowed_ingress_cidrs) > 0 && alltrue([for c in var.allowed_ingress_cidrs : can(cidrhost(c, 0)) && c != "0.0.0.0/0"])
    error_message = "Valid CIDRs, at least one, and not 0.0.0.0/0: this deployment carries passwords over plain HTTP."
  }
}

variable "db_instance_class" {
  description = "Smallest Graviton class; ~$0.016/h (us-east-1 on-demand, estimated)."
  type        = string
  default     = "db.t4g.micro"
}

variable "cache_node_type" {
  description = "Smallest Graviton node; Valkey is priced ~20% below Redis OSS."
  type        = string
  default     = "cache.t4g.micro"
}

variable "image_tag" {
  description = "Full commit SHA of the image in ECR to deploy. CI pushes only SHAs there (immutable tags); `git rev-parse origin/main` after a green main run."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "A full 40-character commit SHA — never a moving tag like main."
  }
}

variable "api_desired_count" {
  description = "API tasks. Two by default: the smallest number that proves cross-instance WebSocket fan-out (ADR-002) on real infrastructure."
  type        = number
  default     = 2
}

variable "worker_use_spot" {
  description = "Run the worker on Fargate Spot. It is interruption-tolerant by design: the outbox is in PostgreSQL and SQS redelivers (ADR-008)."
  type        = bool
  default     = true
}
