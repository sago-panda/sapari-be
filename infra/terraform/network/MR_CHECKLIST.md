# C-2 network MR evidence

## Terraform plan

아래 명령의 출력 전문을 MR 본문 또는 CI artifact에 첨부한다. 개인 IP가 포함된 plan 파일은 커밋하지 않는다.

```bash
terraform plan -no-color -var='allowed_client_cidrs=["<trusted-ip>/32"]'
```

Expected convergence after apply:

```text
No changes. Your infrastructure matches the configuration.
```

## LiveKit ingress rules

| Protocol | Port | Source | Reason |
|---|---:|---|---|
| TCP | 7880 | `allowed_client_cidrs` | HTTP/WebSocket signaling |
| TCP | 7881 | `allowed_client_cidrs` | WebRTC TCP fallback |
| UDP | 7882 | `allowed_client_cidrs` | WebRTC UDP mux media |
| UDP | 50000–60000 | `allowed_client_cidrs` | Per-connection WebRTC media ports |
| TCP | 1935 | `allowed_client_cidrs` | OBS RTMP ingest |

## Checklist

- [x] Public and private subnets span two availability zones
- [x] No NAT Gateway, ALB, EKS, RDS, ElastiCache, or IRSA resource
- [x] Private route tables have no `0.0.0.0/0` route
- [x] S3 Gateway Endpoint is associated with both private route tables
- [x] Ingress rejects `0.0.0.0/0`; trusted CIDRs are runtime variables
- [x] Provider `default_tags` applies Project, Environment, Stack, ManagedBy
- [x] Account-specific backend values are injected during `terraform init`
- [x] No `terraform_remote_state`
- [x] Public internet verification returns HTTP 200
- [x] Private S3 path returns HTTP 200 without NAT or public IP

