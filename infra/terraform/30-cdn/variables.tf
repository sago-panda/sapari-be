variable "aws_region" {
  description = "오리진(S3)이 있는 리전. CloudFront 자체는 글로벌이다"
  type        = string
  default     = "ap-northeast-2"
}

variable "aws_account_id" {
  description = "이 스택이 실행되어야 하는 계정. 오작동 방지용이라 리터럴이어야 한다"
  type        = string
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
