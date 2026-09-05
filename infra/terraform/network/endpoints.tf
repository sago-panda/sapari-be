resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"

  route_table_ids = [
    for route_table in aws_route_table.private : route_table.id
  ]

  tags = {
    Name = "${local.name_prefix}-s3-gateway-endpoint"
  }
}

