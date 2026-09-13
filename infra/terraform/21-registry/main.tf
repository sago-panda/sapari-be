# live-app 컨테이너 이미지 레지스트리.
#
# 빈 리포지터리는 시간당 과금이 없다(저장 500MB/월까지 무료). 그래서
# 유료 창 밖에서 미리 만들어 둔다.
#
# 같은 계정의 EKS/EC2 가 pull 하므로 imagePullSecret 은 필요 없다.
# 노드 역할에 AmazonEC2ContainerRegistryReadOnly 가 붙으면 끝이다.
resource "aws_ecr_repository" "live_app" {
  name = "${var.project}-${var.environment}-live-app"

  # dev 라 latest 를 덮어쓴다. prod 승격 시 IMMUTABLE 로 바꾼다.
  image_tag_mutability = "MUTABLE"

  # 기본 스캔은 무료다. 끌 이유가 없다.
  image_scanning_configuration {
    scan_on_push = true
  }

  # 이미지가 남아 있으면 destroy 가 실패한다. 20-storage 의 버킷
  # force_destroy = true 와 같은 이유(단기 검증 환경)다.
  force_delete = true
}
