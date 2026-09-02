# 로컬 docker egress 전용 사용자.
#
# 액세스 키의 secret 은 state 에 평문으로 저장된다. 지금은 state 버킷을 읽을 수
# 있는 사람이 한 명뿐이고, 이 키의 권한이 이 버킷의 live/ prefix 쓰기로 한정되어
# 있어 감수한다.
#
# 만료 조건: EKS 도입 시 IRSA 로 교체하고 이 사용자를 삭제한다.
# 그 전에 Terraform 을 쓰는 사람이 둘 이상이 되면 이 전제가 깨진다 -
# 사람용 자격증명은 여기 추가하지 말고 IAM Identity Center 로 분리할 것.
resource "aws_iam_user" "media_uploader" {
  name = "${var.project}-${var.environment}-media-uploader"
}

resource "aws_iam_access_key" "media_uploader" {
  user = aws_iam_user.media_uploader.name
}

data "aws_iam_policy_document" "media_uploader" {
  # 업로드. Resource 를 버킷이 아니라 live/ prefix 까지 좁힌다.
  # 여기서 "${arn}/*" 로 쓰면 다른 prefix 에도 쓸 수 있게 된다.
  statement {
    sid    = "PutHlsObjects"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      # egress 가 큰 파일을 멀티파트로 올린다. 중단 권한이 없으면 실패한
      # 업로드 조각을 스스로 정리하지 못한다.
      "s3:AbortMultipartUpload",
    ]

    resources = ["${aws_s3_bucket.media.arn}/${var.media_key_prefix}*"]
  }

  # 목록 조회는 버킷 단위 액션이라 대상이 버킷 ARN 이다.
  # 조건으로 live/ 아래만 보이게 제한한다.
  statement {
    sid       = "ListOwnPrefixOnly"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.media.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.media_key_prefix}*"]
    }
  }
}

resource "aws_iam_user_policy" "media_uploader" {
  name   = "${var.project}-${var.environment}-media-uploader"
  user   = aws_iam_user.media_uploader.name
  policy = data.aws_iam_policy_document.media_uploader.json
}
