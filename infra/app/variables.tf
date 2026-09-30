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
