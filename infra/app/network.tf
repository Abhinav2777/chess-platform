# Two tiers, two AZs, no NAT gateway (ADR-010, DEPLOYMENT.md rule 3).
#
#   public   10.0.0.0/24, 10.0.1.0/24   ALB + ECS tasks (tasks get a public IP for egress:
#                                        ECR, SQS, Secrets Manager, CloudWatch — the job a NAT
#                                        gateway would do for ~$33/month plus data charges)
#   isolated 10.0.10.0/24, 10.0.11.0/24 RDS + ElastiCache: no route to or from the internet
#
# Two AZs is not for availability here (one RDS instance, one cache node): the ALB and the
# RDS/ElastiCache subnet groups each require subnets in at least two AZs.

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

resource "aws_vpc" "main" {
  cidr_block = var.vpc_cidr
  # RDS and ElastiCache endpoints are DNS names that resolve to private addresses.
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = var.name }
}

resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index) # 10.0.0.0/24, 10.0.1.0/24
  # No automatic public IPs: the ECS service asks for one explicitly (7.4), so nothing else
  # launched here is internet-addressable by accident.
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public-${local.azs[count.index]}", Tier = "public" }
}

resource "aws_subnet" "isolated" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 10 + count.index) # 10.0.10.0/24, 10.0.11.0/24
  tags              = { Name = "${var.name}-isolated-${local.azs[count.index]}", Tier = "isolated" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Only the implicit local route: the data tier cannot reach the internet, and nothing on the
# internet can route to it — independent of any security group being right.
resource "aws_route_table" "isolated" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-isolated" }
}

resource "aws_route_table_association" "isolated" {
  count          = 2
  subnet_id      = aws_subnet.isolated[count.index].id
  route_table_id = aws_route_table.isolated.id
}

# Every VPC comes with a default security group that allows all traffic between its members.
# Nothing here uses it; taking it over with no rules means nothing can by accident.
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-default-unused" }
}
