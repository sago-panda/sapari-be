terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # 00-bootstrap 이 만든 버킷을 쓴다. 계정번호가 들어간 버킷 이름을 소스에
  # 남기지 않으려고 init 시 -backend-config 로 주입한다(network 스택과 같은 방식).
  #   terraform init -backend-config=backend.hcl
  backend "s3" {}
}
