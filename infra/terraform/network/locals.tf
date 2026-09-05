locals {
  name_prefix = "sapari-${var.environment}"

  common_tags = merge(var.additional_tags, {
    Project     = "sapari-be"
    Environment = var.environment
    Stack       = "network"
    ManagedBy   = "terraform"
  })

  subnets = {
    for index, az in var.availability_zones : az => {
      public_cidr  = var.public_subnet_cidrs[index]
      private_cidr = var.private_subnet_cidrs[index]
    }
  }
}

