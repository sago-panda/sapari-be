output "distribution_domain_name" {
  description = "HLS 재생 도메인 (dxxxx.cloudfront.net)"
  value       = aws_cloudfront_distribution.media.domain_name
}

output "distribution_id" {
  description = "CloudFront 배포 ID"
  value       = aws_cloudfront_distribution.media.id
}

output "origin_bucket" {
  description = "오리진으로 쓰는 미디어 버킷 (20-storage 소유)"
  value       = data.aws_s3_bucket.media.id
}

output "ssm_parameter_prefix" {
  description = "재생 주소가 들어 있는 SSM 경로"
  value       = local.ssm_prefix
}
