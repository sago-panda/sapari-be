# 버킷이 CloudFront 에게 읽기를 허락하는 지점. 이게 없으면 CloudFront 경유도
# 403 이다(배포만으로는 권한이 생기지 않는다).
#
# 이 정책이 20-storage 소유의 버킷에 붙는 건 의도적이다. 정책 내용에 이 스택이
# 만드는 배포 ARN 이 들어가야 해서 여기 둘 수밖에 없다. 반대로 두면 storage 가
# cdn 을 기다리게 되어 상시 스택끼리 순환이 생긴다.
#
# ⚠️ aws_s3_bucket_policy 는 버킷 정책 전체를 덮어쓴다. 20-storage 에 버킷 정책을
#    추가하면 두 스택이 apply 할 때마다 서로를 지운다. 미디어 버킷의 정책은
#    이 파일 하나만 소유한다.
data "aws_iam_policy_document" "media_oac_read" {
  statement {
    sid    = "AllowCloudFrontOacRead"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    # 재생은 읽기뿐이다. CloudFront 가 버킷에 쓸 일은 없다.
    actions = ["s3:GetObject"]

    # 버킷 전체가 아니라 egress 가 올리는 prefix 아래만.
    # 20-storage 의 업로드 정책이 같은 방식으로 좁혀져 있다.
    resources = ["${data.aws_s3_bucket.media.arn}/${data.aws_ssm_parameter.media_key_prefix.value}*"]

    # 이 조건이 없으면 같은 계정의 아무 CloudFront 배포나 이 버킷을 읽는다.
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.media.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "media_oac_read" {
  bucket = data.aws_s3_bucket.media.id
  policy = data.aws_iam_policy_document.media_oac_read.json
}
