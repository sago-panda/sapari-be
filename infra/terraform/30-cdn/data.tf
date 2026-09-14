# 20-storage 가 만든 미디어 버킷을 오리진으로 쓴다.
#
# terraform_remote_state 를 쓰지 않는다 - 읽는 쪽이 쓰는 쪽의 state 구조를 알게
# 되고, state 전체(시크릿 포함)에 접근하게 된다. 20-storage 가 이미 SSM 으로
# 계약을 내보내고 있으니 그걸 읽는다.
data "aws_ssm_parameter" "media_bucket" {
  name = "/${var.project}/${var.environment}/media/bucket"
}

data "aws_ssm_parameter" "media_key_prefix" {
  name = "/${var.project}/${var.environment}/media/key-prefix"
}

# 오리진 주소를 "${bucket}.s3.${region}.amazonaws.com" 으로 조립하지 않는다.
# S3 도메인 형식은 리전과 버킷 생성 시점에 따라 다르고, 틀리면 CloudFront 가
# 307 리다이렉트를 뱉거나 루프에 빠진다. AWS 가 주는 값을 그대로 쓴다.
# (state 를 읽는 게 아니라 AWS API 에 버킷을 묻는 것이므로 위 금지와 무관하다)
data "aws_s3_bucket" "media" {
  bucket = data.aws_ssm_parameter.media_bucket.value
}
