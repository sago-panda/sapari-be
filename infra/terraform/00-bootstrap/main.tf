locals {
  # 전역에서 유일해야 하므로 계정번호를 붙임
  # destroy -> apply 때 이름이 바뀌면 backend 설정과 다른 스택 참조가 깨짐
  # ap-northeast-2 전용으로 apne2를 붙임
  state_bucket_name = "${var.project}-tfstate-${data.aws_caller_identity.current.account_id}-apne2"
}

resource "aws_s3_bucket" "tfstate" {
  bucket = local.state_bucket_name

  # state가 든 버킷이라 destroy를 코드로 막음
  # 정말 지울 땐 이 줄을 지워야 한다.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {

      # AES256(s3 관리 키). KMS는 요청당 과금이 붙어 이 규모에서는 불필요하다 판단.
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}