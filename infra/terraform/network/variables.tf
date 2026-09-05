variable "aws_region" {
  description = "AWS region in which the network stack is created."
  type        = string
  default     = "ap-northeast-2"

  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "This stack is designed for ap-northeast-2."
  }
}

variable "environment" {
  description = "Environment name used in resource names and tags."
  type        = string
  default     = "live-validation"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.environment))
    error_message = "environment must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "vpc_cidr" {
  description = "IPv4 CIDR assigned to the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "availability_zones" {
  description = "Two distinct availability zones used by both public and private subnets."
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]

  validation {
    condition = (
      length(var.availability_zones) == 2 &&
      length(toset(var.availability_zones)) == 2 &&
      alltrue([for az in var.availability_zones : startswith(az, "ap-northeast-2")])
    )
    error_message = "Provide exactly two distinct availability zones in ap-northeast-2."
  }
}

variable "public_subnet_cidrs" {
  description = "CIDRs for public subnets, ordered to match availability_zones."
  type        = list(string)
  default     = ["10.20.0.0/24", "10.20.1.0/24"]

  validation {
    condition     = length(var.public_subnet_cidrs) == 2
    error_message = "Provide exactly two public subnet CIDRs."
  }
}

variable "private_subnet_cidrs" {
  description = "CIDRs for private subnets, ordered to match availability_zones."
  type        = list(string)
  default     = ["10.20.16.0/20", "10.20.32.0/20"]

  validation {
    condition     = length(var.private_subnet_cidrs) == 2
    error_message = "Provide exactly two private subnet CIDRs."
  }
}

variable "allowed_client_cidrs" {
  description = "Trusted public IPv4 CIDRs allowed to reach every LiveKit ingress port. Use /32 for test clients."
  type        = set(string)

  validation {
    condition = (
      length(var.allowed_client_cidrs) > 0 &&
      alltrue([for cidr in var.allowed_client_cidrs : can(cidrnetmask(cidr))]) &&
      !contains(var.allowed_client_cidrs, "0.0.0.0/0")
    )
    error_message = "Provide at least one valid IPv4 CIDR; 0.0.0.0/0 is intentionally forbidden for this validation stack."
  }
}

variable "additional_tags" {
  description = "Additional tags merged into every taggable resource. Reserved common tag keys cannot be overridden."
  type        = map(string)
  default     = {}

  validation {
    condition = length(setintersection(
      toset(keys(var.additional_tags)),
      toset(["Project", "Environment", "Stack", "ManagedBy"])
    )) == 0
    error_message = "additional_tags cannot override Project, Environment, Stack, or ManagedBy."
  }
}

