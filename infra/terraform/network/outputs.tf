output "vpc_id" {
  description = "ID of the network VPC."
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "Public subnet IDs keyed by availability zone."
  value       = { for az, subnet in aws_subnet.public : az => subnet.id }
}

output "private_subnet_ids" {
  description = "Private subnet IDs keyed by availability zone."
  value       = { for az, subnet in aws_subnet.private : az => subnet.id }
}

output "private_route_table_ids" {
  description = "Private route table IDs keyed by availability zone."
  value       = { for az, route_table in aws_route_table.private : az => route_table.id }
}

output "s3_gateway_endpoint_id" {
  description = "ID of the S3 gateway VPC endpoint attached to private route tables."
  value       = aws_vpc_endpoint.s3.id
}

output "livekit_security_group_id" {
  description = "Security group to attach to LiveKit SFU instances."
  value       = aws_security_group.livekit.id
}

output "livekit_ingress_rules" {
  description = "Auditable summary of the five allowed LiveKit port/protocol combinations."
  value = {
    for name, rule in local.livekit_ingress_rules : name => {
      protocol     = rule.ip_protocol
      port_range   = rule.from_port == rule.to_port ? tostring(rule.from_port) : "${rule.from_port}-${rule.to_port}"
      source_cidrs = sort(tolist(var.allowed_client_cidrs))
      reason       = rule.description
    }
  }
}

