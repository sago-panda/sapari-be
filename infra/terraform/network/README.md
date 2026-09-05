# C-2 network stack

서울 리전의 LiveKit 검증과 향후 EKS 배치를 위한 상시 네트워크 스택이다. VPC, 2개 AZ의
public/private subnet, Internet Gateway, route table, 무료 S3 Gateway Endpoint, LiveKit security
group을 만든다. NAT Gateway, ALB, EKS, RDS는 만들지 않는다.

## Address plan

| Tier | Availability zone | CIDR |
|---|---|---|
| public | `ap-northeast-2a` | `10.20.0.0/24` |
| public | `ap-northeast-2c` | `10.20.1.0/24` |
| private | `ap-northeast-2a` | `10.20.16.0/20` |
| private | `ap-northeast-2c` | `10.20.32.0/20` |

VPC CIDR은 `10.20.0.0/16`이다. EKS VPC CNI가 Pod별 VPC IP를 소비하므로 private subnet을
public subnet보다 크게 두었다.

## Usage

실제 backend 값과 테스트 클라이언트 IP는 소스에 저장하지 않는다.

```bash
cd infra/terraform/network

terraform init -reconfigure \
  -backend-config="bucket=<C-1 state bucket>" \
  -backend-config="key=network/terraform.tfstate" \
  -backend-config="region=ap-northeast-2" \
  -backend-config="encrypt=true" \
  -backend-config="use_lockfile=true"

terraform plan \
  -var='allowed_client_cidrs=["<your-public-ip>/32"]' \
  -out=network.tfplan
terraform apply network.tfplan
```

`allowed_client_cidrs`에는 OBS 송출자와 테스트 시청자의 공인 IPv4를 `/32`로 추가한다. 이 스택은
`0.0.0.0/0` 입력을 거부한다. 실제 불특정 다수 시청자를 받기 시작하면 UDP 미디어 포트와 TCP fallback의
공개 범위를 별도로 재검토해야 한다.

## Verification

다음 스크립트는 public EC2의 인터넷 HTTP 200과 private EC2의 S3 HTTP 200을 확인한다.

```bash
./scripts/verify-network.sh
```

스크립트는 다음 임시 자원을 만든 뒤 성공·실패와 관계없이 종료 훅으로 삭제한다.

- ARM 기반 `t4g.nano` EC2 두 대
- 검증 결과 prefix에만 `s3:PutObject` 가능한 IAM role과 instance profile
- C-3a의 `/sapari/dev/media/bucket` SSM 계약에서 읽은 버킷 안의 임시 결과 객체

SSH 인바운드는 만들지 않는다. Private EC2는 public IP와 NAT 없이 S3 Gateway Endpoint만 사용한다.

## ECR without NAT

S3 Gateway Endpoint는 S3 트래픽만 처리한다. Private EKS node가 ECR image를 pull하려면 ECR API,
ECR Docker registry와 일반적으로 CloudWatch Logs용 Interface Endpoint가 추가로 필요하다. Interface
Endpoint는 AZ별 ENI와 시간당 비용이 생긴다.

검증 단계의 더 단순하고 저렴한 선택은 미디어 EC2를 public subnet에 두고 public IP를 부여하되,
security group을 필요한 LiveKit 포트와 신뢰 CIDR로 제한하는 것이다. 다만 public exposure와 patching
책임이 커진다. EKS 단계에서는 예상 실행 시간과 데이터 양을 기준으로 Interface Endpoint 비용과 NAT
비용을 다시 비교한다.

## MR evidence

[`MR_CHECKLIST.md`](MR_CHECKLIST.md)에 plan 전문, 실제 security group 조회 결과, 태그 점검 결과를 붙인다.
현재 공인 IP가 포함될 수 있으므로 생성된 `terraform-plan.txt`는 gitignore 대상이다.
