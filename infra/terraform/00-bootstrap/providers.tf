provider "aws" {
  region = var.aws_region

  # 다른 계정 자격증명으로 실행되는 사고를 막는다. 접근 제어가 아니라 오작동 방지.
  # 여기서는 data 가 아니라 리터럴이어야 한다 - data 를 쓰면 "지금 계정이 지금 계정인가"라
  # 항상 통과한다.
  allowed_account_ids = ["316708313927"]

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = var.owner
    }
  }
}

data "aws_caller_identity" "current" {}