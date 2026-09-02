terraform {
  required_version = ">=1.10"

  backend "s3" {
    bucket       = "sapari-tfstate-316708313927-apne2"
    key          = "00-bootstrap/terraform.tfstate"
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