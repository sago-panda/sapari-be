locals {
  # tfstate 버킷과 같은 규칙. S3 이름은 전역 유일이라 계정번호를 붙인다.
  media_bucket_name = "${var.project}-${var.environment}-media-${data.aws_caller_identity.current.account_id}-apne2"
}

resource "aws_s3_bucket" "media" {
  bucket = local.media_bucket_name

  # D4 - 테스트 환경. 객체가 남으면 destroy 가 실패해 잔여 과금이 생긴다.
  # tfstate 버킷과 정반대의 결정이다(거긴 prevent_destroy).
  force_destroy = true
}

# D3 - 버저닝을 켜지 않는다.
# HLS 세그먼트는 2초마다 생성된다. 버저닝하면 삭제해도 이전 버전이 남아
# 비용이 누적되고, 라이프사이클로 정리하려면 noncurrent 규칙까지 따로 써야 한다.
# (버저닝 리소스를 아예 만들지 않으면 S3 기본값인 Disabled 로 남는다)

resource "aws_s3_bucket_public_access_block" "media" {
  bucket = aws_s3_bucket.media.id

  # 재생은 나중에 CloudFront(OAC)로 내보낸다. 버킷 자체는 끝까지 비공개다.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# D5 - 라이프사이클. 이게 없으면 방치된 세그먼트가 조용히 과금된다.
resource "aws_s3_bucket_lifecycle_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  # HLS 세그먼트는 방송이 끝나면 재생 가치가 없다. 짧게 만료시킨다.
  rule {
    id     = "expire-hls-segments"
    status = "Enabled"

    filter {
      prefix = var.media_key_prefix
    }

    expiration {
      days = var.media_expiration_days
    }
  }

  # egress 가 중간에 죽으면 멀티파트 조각이 남는다. 이건 객체 목록에
  # 보이지 않으면서 스토리지 요금은 나가서, 안 지우면 원인을 찾기 어렵다.
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
