# CloudFront 가 S3 를 읽을 때 쓰는 신분증.
#
# CloudFront 가 오리진 요청에 SigV4 서명을 붙이고, 버킷 정책이 "이 배포에서 온
# 서명"만 허용한다(policy.tf). 덕분에 버킷은 끝까지 비공개로 둘 수 있다.
#
# 선행 방식이던 OAI 를 쓰지 않는 이유: OAI 는 SSE-KMS 버킷을 읽지 못하고
# AWS 가 신규 사용을 권장하지 않는다.
resource "aws_cloudfront_origin_access_control" "media" {
  name                              = "${var.project}-${var.environment}-media"
  description                       = "미디어 버킷을 CloudFront 에서만 읽기 위한 OAC"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
