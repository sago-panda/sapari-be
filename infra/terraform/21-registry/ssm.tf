# 다른 스택과 배포 매니페스트가 읽어갈 계약.
# terraform_remote_state 를 쓰지 않는다 - 읽는 쪽이 쓰는 쪽의 state 구조를
# 알게 되고, state 전체(시크릿 포함)에 접근하게 된다. 20-storage 와 같은 규칙.
locals {
  ssm_prefix = "/${var.project}/${var.environment}/registry"
}

resource "aws_ssm_parameter" "repository_url" {
  name        = "${local.ssm_prefix}/repository-url"
  description = "live-app 이미지 주소. 매니페스트에 하드코딩하지 않기 위한 자리"
  type        = "String"
  value       = aws_ecr_repository.live_app.repository_url
}

resource "aws_ssm_parameter" "repository_name" {
  name        = "${local.ssm_prefix}/repository-name"
  type        = "String"
  value       = aws_ecr_repository.live_app.name
}
