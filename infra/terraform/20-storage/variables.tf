variable "aws_region" {
  description = "모든 리소스를 만들 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project" {
  description = "Project 태그 값"
  type        = string
  default     = "sapari"
}

variable "environment" {
  description = "Environment 태그 값"
  type        = string
  default     = "dev"
}

variable "owner" {
  description = "Owner 태그 값 - 이 리소스에 대해 물어볼 사람"
  type        = string
}

variable "media_key_prefix" {
  description = "egress 가 HLS 를 올리는 prefix. live-app 의 S3_KEY_PREFIX 와 같아야 한다"
  type        = string
  default     = "live/"
}

variable "media_expiration_days" {
  description = "HLS 세그먼트 보관 일수. 검증용이라 짧게 잡는다"
  type        = number
  default     = 7
}
