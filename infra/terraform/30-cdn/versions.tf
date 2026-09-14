terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # 계정번호가 들어간 버킷 이름을 소스에 남기지 않으려고 init 시 주입한다.
  #   terraform init -backend-config=backend.hcl
  backend "s3" {}
}
