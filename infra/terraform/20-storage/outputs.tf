output "media_bucket" {
  description = "미디어 버킷 이름 (live-app 의 S3_BUCKET)"
  value       = aws_s3_bucket.media.id
}

output "media_uploader_user" {
  description = "egress 업로드 전용 IAM 사용자"
  value       = aws_iam_user.media_uploader.name
}

output "ssm_parameter_prefix" {
  description = "버킷 정보와 자격증명이 들어 있는 SSM 경로"
  value       = local.ssm_prefix
}

# secret 은 output 으로 내보내지 않는다. output 은 마스킹 없이 콘솔과 CI 로그에
# 그대로 찍힌다. 값이 필요하면 SSM 에서 꺼내 쓴다:
#   aws ssm get-parameter --name /sapari/dev/media/secret-access-key --with-decryption
output "access_key_id" {
  description = "액세스 키 ID (secret 아님)"
  value       = aws_iam_access_key.media_uploader.id
}
