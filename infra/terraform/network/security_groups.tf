locals {
  livekit_ingress_rules = {
    signaling = {
      description = "LiveKit signaling over HTTP or WebSocket"
      ip_protocol = "tcp"
      from_port   = 7880
      to_port     = 7880
    }
    webrtc_tcp = {
      description = "WebRTC transport fallback over TCP"
      ip_protocol = "tcp"
      from_port   = 7881
      to_port     = 7881
    }
    webrtc_udp = {
      description = "WebRTC media mux over UDP"
      ip_protocol = "udp"
      from_port   = 7882
      to_port     = 7882
    }
    webrtc_udp_range = {
      description = "WebRTC per-connection media ports over UDP"
      ip_protocol = "udp"
      from_port   = 50000
      to_port     = 60000
    }
    rtmp = {
      description = "RTMP ingest from OBS"
      ip_protocol = "tcp"
      from_port   = 1935
      to_port     = 1935
    }
  }

  livekit_ingress_rules_by_cidr = {
    for pair in setproduct(keys(local.livekit_ingress_rules), var.allowed_client_cidrs) :
    "${pair[0]}-${replace(replace(pair[1], ".", "_"), "/", "_")}" => merge(
      local.livekit_ingress_rules[pair[0]],
      { cidr_ipv4 = pair[1] }
    )
  }
}

resource "aws_security_group" "livekit" {
  name_prefix = "${local.name_prefix}-livekit-"
  description = "LiveKit SFU ingress restricted to explicitly trusted test clients"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name_prefix}-livekit-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "livekit" {
  for_each = local.livekit_ingress_rules_by_cidr

  security_group_id = aws_security_group.livekit.id
  description       = each.value.description
  ip_protocol       = each.value.ip_protocol
  from_port         = each.value.from_port
  to_port           = each.value.to_port
  cidr_ipv4         = each.value.cidr_ipv4

  tags = {
    Name = "${local.name_prefix}-${each.key}"
  }
}

# Outbound traffic is unrestricted at the SG layer. Public instances still need
# an IGW route, while private instances can only reach destinations present in
# their route table (currently the VPC itself and S3 through the endpoint).
resource "aws_vpc_security_group_egress_rule" "livekit_ipv4" {
  security_group_id = aws_security_group.livekit.id
  description       = "Outbound IPv4; effective destinations are constrained by subnet routes"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"

  tags = {
    Name = "${local.name_prefix}-livekit-egress-ipv4"
  }
}

