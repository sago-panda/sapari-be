variable "aws_region" {
  description = "모든 리소스 만들 리전"
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

variable "alert_email" {
  description = "예산 알림을 받을 이메일"
  type        = string
}