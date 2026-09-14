# 플레이어가 다른 오리진(로컬 개발 서버, 프론트 도메인)에서 붙으므로
# 브라우저가 CORS 응답 헤더를 요구한다.
#
# S3 버킷의 CORS 설정에 의존하지 않는다 - 캐시에서 나가는 응답에는 오리진이
# 붙인 헤더가 실리지 않아, 캐시 적중 여부에 따라 CORS 가 되다 말다 한다.
# CloudFront 가 직접 붙이면 캐시 적중이든 아니든 항상 실린다.
resource "aws_cloudfront_response_headers_policy" "media_cors" {
  name    = "${var.project}-${var.environment}-media-cors"
  comment = "HLS 플레이어용 CORS 헤더"

  cors_config {
    # 인증 없는 공개 재생이고 쿠키를 쓰지 않는다. 프론트 도메인이 정해지면 좁힌다.
    access_control_allow_origins { items = ["*"] }

    # 재생에 필요한 건 읽기뿐이다.
    access_control_allow_methods { items = ["GET", "HEAD", "OPTIONS"] }

    access_control_allow_headers { items = ["*"] }

    # allow_origins 가 "*" 인 이상 자격증명은 실을 수 없다(브라우저가 거부한다).
    access_control_allow_credentials = false

    access_control_max_age_sec = 3600

    # S3 가 이미 CORS 헤더를 보냈더라도 여기 설정으로 덮어쓴다.
    # 두 곳에서 서로 다른 값이 나가는 상황을 막는다.
    origin_override = true
  }
}
