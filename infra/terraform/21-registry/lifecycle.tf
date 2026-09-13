# 저장요금 방어. 무료 한도가 500MB/월인데 live-app 이미지 하나가 300~400MB 라
# 이 규칙이 없으면 몇 번 push 하는 것만으로 한도를 넘는다.
#
# 규칙은 priority 오름차순으로 평가되고, 한 이미지는 먼저 맞은 규칙 하나에만
# 걸린다. 그래서 좁은 조건(untagged)을 앞에 둔다.
resource "aws_ecr_lifecycle_policy" "live_app" {
  repository = aws_ecr_repository.live_app.name

  policy = jsonencode({
    rules = [
      {
        # 같은 태그로 다시 push 하면 이전 이미지에서 태그가 떨어져 나온다.
        # 목록에 이름 없이 남으면서 저장요금은 계속 나가는 게 이것들이다.
        # 하루를 두는 건 방금 떨어진 이미지로 되돌릴 여지를 남기기 위해서다.
        rulePriority = 1
        description  = "태그를 잃은 이미지는 1일 뒤 삭제"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        # 지금은 latest 만 쓰므로 태그 달린 이미지가 1개뿐이라 이 규칙은
        # 걸리지 않는다. CI 가 커밋 해시로 태그를 붙이기 시작하면 그때부터
        # 롤백용 10개를 남기고 그 아래를 정리한다.
        rulePriority = 2
        description  = "태그 달린 이미지는 최근 10개만 보관"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 10
        }
        action = { type = "expire" }
      },
    ]
  })
}
