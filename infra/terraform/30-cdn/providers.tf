# CloudFront 는 글로벌 서비스라 리전을 고르지 않는다. 엣지는 서울을 포함해
# 전 세계에 깔려 있고, 오리진(S3)도 ap-northeast-2 그대로다.
#
# us-east-1 provider 가 필요해지는 경우는 하나뿐이다 - 커스텀 도메인을 붙일 때.
# CloudFront 는 us-east-1 에서 발급한 ACM 인증서만 인식한다(관리 API 가 거기
# 있어서 생긴 제약이고 트래픽 경로와는 무관하다). 지금은 커스텀 도메인 없이
# 기본 도메인 dxxxx.cloudfront.net 을 쓰므로 인증서 자체가 없다.
provider "aws" {
  region = var.aws_region

  # 다른 계정 자격증명으로 실행되는 사고를 막는다. 접근 제어가 아니라 오작동 방지.
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = var.owner
    }
  }
}
