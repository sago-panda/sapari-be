output "repository_url" {
  description = "docker push 대상 (ECR 리포지터리 URL)"
  value       = aws_ecr_repository.live_app.repository_url
}

output "repository_name" {
  description = "ECR 리포지터리 이름"
  value       = aws_ecr_repository.live_app.name
}

output "ssm_parameter_prefix" {
  description = "레지스트리 정보가 들어 있는 SSM 경로"
  value       = local.ssm_prefix
}
