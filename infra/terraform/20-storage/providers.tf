provider "aws" {
  region = var.aws_region

  # 다른 계정 자격증명으로 실행되는 사고를 막는다. 접근 제어가 아니라 오작동 방지.
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
