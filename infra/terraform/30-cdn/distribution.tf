locals {
  origin_id = "media-s3"
}

resource "aws_cloudfront_distribution" "media" {
  enabled = true
  comment = "${var.project}-${var.environment} HLS 재생"

  # 북미·유럽만 쓰는 PriceClass_100 이 제일 싸지만, 그걸 고르면 한국 시청자가
  # 아시아 엣지를 못 쓰고 미국/유럽으로 돌아간다 - CDN 을 붙이고 오히려 느려진다.
  # _200 은 아시아를 포함한다. 월 1TB 까지 무료라 검증 단계에선 요금 차이가 없다.
  price_class = "PriceClass_200"

  # 커스텀 도메인(aliases)을 쓰지 않으므로 AWS 기본 인증서를 그대로 쓴다.
  # 도메인이 정해지면 여기에 aliases + acm_certificate_arn(us-east-1 발급)이 붙는다.
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  origin {
    domain_name              = data.aws_s3_bucket.media.bucket_regional_domain_name
    origin_id                = local.origin_id
    origin_access_control_id = aws_cloudfront_origin_access_control.media.id
  }

  # 기본 동작 = 세그먼트(1일). 기본값을 긴 쪽에 두는 게 중요하다.
  # 반대로 두면 예상 못 한 확장자가 1초 캐싱으로 떨어져 요금이 새는 방향으로 틀린다.
  default_cache_behavior {
    target_origin_id = local.origin_id
    allowed_methods  = ["GET", "HEAD", "OPTIONS"]
    cached_methods   = ["GET", "HEAD"]

    viewer_protocol_policy     = "redirect-to-https"
    cache_policy_id            = aws_cloudfront_cache_policy.hls_segment.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.media_cors.id

    # 프리플라이트는 여기서 끝난다. 이유는 cors_preflight.js 주석 참조.
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.cors_preflight.arn
    }
  }

  # 플레이리스트만 짧게. 이 분기가 없으면 재생이 멈추거나 요금이 샌다.
  ordered_cache_behavior {
    path_pattern     = "*.m3u8"
    target_origin_id = local.origin_id
    allowed_methods  = ["GET", "HEAD", "OPTIONS"]
    cached_methods   = ["GET", "HEAD"]

    viewer_protocol_policy     = "redirect-to-https"
    cache_policy_id            = aws_cloudfront_cache_policy.hls_playlist.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.media_cors.id

    # 텍스트라 압축이 유효하다. 캐시 정책의 accept-encoding 설정과 짝이다.
    compress = true

    # 프리플라이트는 여기서 끝난다. 이유는 cors_preflight.js 주석 참조.
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.cors_preflight.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # 액세스 로그는 켜지 않는다. S3 저장 요금이 붙고 지금 볼 사람이 없다.
  # 필요해지면 logging_config 를 추가한다.
}
