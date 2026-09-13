# 월 200만 호출까지 무료. 프리플라이트는 브라우저가 max-age(1시간) 동안
# 캐싱하므로 호출 수 자체가 적다.
resource "aws_cloudfront_function" "cors_preflight" {
  name    = "${var.project}-${var.environment}-cors-preflight"
  runtime = "cloudfront-js-2.0"
  comment = "OPTIONS 프리플라이트를 엣지에서 204 로 응답"
  publish = true
  code    = file("${path.module}/cors_preflight.js")
}
