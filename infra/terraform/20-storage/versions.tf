terraform {
  required_version = ">=1.10"

  # 00-bootstrap 이 만든 버킷을 그대로 쓴다. key 만 다르면 스택이 분리된다.
  # 한 버킷 안에서 경로로 나누는 것이 스택마다 버킷을 만드는 것보다 관리가 쉽다.
  backend "s3" {
    bucket       = "sapari-tfstate-316708313927-apne2"
    key          = "20-storage/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
