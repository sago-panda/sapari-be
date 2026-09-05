terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # C-1에서 만든 S3 backend 값은 init 시 -backend-config로 주입한다.
  # backend 설정에는 변수를 사용할 수 없으므로 계정별 값을 소스에 넣지 않는다.
  backend "s3" {}
}

