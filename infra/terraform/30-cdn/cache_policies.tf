# HLS 는 성격이 정반대인 두 파일로 굴러간다. 캐시 정책을 하나로 합치면
# 둘 중 하나가 반드시 망가진다 - 플레이리스트를 오래 잡으면 재생이 멈추고,
# 세그먼트를 짧게 잡으면 S3 요청 요금이 샌다.
#
# 캐시 키에는 쿠키/쿼리스트링/헤더를 넣지 않는다. 같은 URL 이면 누가 요청하든
# 같은 파일이라, 키에 뭘 더 넣으면 캐시만 쪼개지고 적중률이 떨어진다.

# 플레이리스트. 2초마다 갱신되는 "지금 재생 가능한 세그먼트 목록"이다.
# 낡은 목록을 주면 플레이어가 다음 세그먼트를 못 찾고 재생이 멈춘다.
resource "aws_cloudfront_cache_policy" "hls_playlist" {
  name        = "${var.project}-${var.environment}-hls-playlist"
  comment     = ".m3u8 - 계속 갱신되므로 1초"
  min_ttl     = 0
  default_ttl = 1
  max_ttl     = 1

  parameters_in_cache_key_and_forwarded_to_origin {
    # 텍스트라 잘 줄어든다.
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true

    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}

# 세그먼트. 한 번 만들어지면 내용이 바뀌지 않는다.
# 길게 잡을수록 S3 요청 수와 전송량이 줄어든다.
resource "aws_cloudfront_cache_policy" "hls_segment" {
  name        = "${var.project}-${var.environment}-hls-segment"
  comment     = ".ts - 불변이므로 1일"
  min_ttl     = 0
  default_ttl = 86400
  max_ttl     = 86400

  parameters_in_cache_key_and_forwarded_to_origin {
    # 이미 압축된 영상이다. 켜도 안 줄고 CPU 만 쓴다.
    enable_accept_encoding_gzip   = false
    enable_accept_encoding_brotli = false

    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}
