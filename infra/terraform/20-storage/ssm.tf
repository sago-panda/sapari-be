# 다른 스택과 앱이 읽어갈 계약.
# 버킷 이름을 코드나 매니페스트에 하드코딩하지 않기 위한 자리다.
# 이름은 live-app 의 환경변수(S3_BUCKET / S3_REGION / ...)와 1:1로 맞춘다.
locals {
  ssm_prefix = "/${var.project}/${var.environment}/media"
}

resource "aws_ssm_parameter" "bucket" {
  name  = "${local.ssm_prefix}/bucket"
  type  = "String"
  value = aws_s3_bucket.media.id
}

resource "aws_ssm_parameter" "region" {
  name  = "${local.ssm_prefix}/region"
  type  = "String"
  value = var.aws_region
}

resource "aws_ssm_parameter" "key_prefix" {
  name  = "${local.ssm_prefix}/key-prefix"
  type  = "String"
  value = var.media_key_prefix
}

resource "aws_ssm_parameter" "access_key_id" {
  name  = "${local.ssm_prefix}/access-key-id"
  type  = "String"
  value = aws_iam_access_key.media_uploader.id
}

# SecureString - KMS 로 암호화되어 저장된다(계정 기본 키 aws/ssm, 추가 비용 없음).
# state 에는 여전히 평문으로 남는다. iam.tf 상단 주석 참조.
resource "aws_ssm_parameter" "secret_access_key" {
  name  = "${local.ssm_prefix}/secret-access-key"
  type  = "SecureString"
  value = aws_iam_access_key.media_uploader.secret
}
