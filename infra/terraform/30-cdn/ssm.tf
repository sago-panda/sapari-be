# 앱과 프론트가 읽어갈 계약. 20-storage 의 /sapari/dev/media/* 와 같은 규칙으로
# 확장한다. 재생 주소를 코드나 매니페스트에 하드코딩하지 않기 위한 자리다.
locals {
  ssm_prefix = "/${var.project}/${var.environment}/cdn"
}

resource "aws_ssm_parameter" "domain_name" {
  name        = "${local.ssm_prefix}/domain-name"
  description = "HLS 재생 도메인. 커스텀 도메인을 붙이면 이 값만 바뀐다"
  type        = "String"
  value       = aws_cloudfront_distribution.media.domain_name
}

resource "aws_ssm_parameter" "distribution_id" {
  name        = "${local.ssm_prefix}/distribution-id"
  description = "무효화(invalidation) 등 운영 작업에 쓰는 배포 ID"
  type        = "String"
  value       = aws_cloudfront_distribution.media.id
}
