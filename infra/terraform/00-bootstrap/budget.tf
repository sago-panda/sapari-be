resource "aws_sns_topic" "budget_alerts" {
  name = "${var.project}-${var.environment}-budget-alerts"
}

# SNS 토픽은 기본적으로 소유 계정만 발행할 수 있음
# Budgets 서비스가 이 토픽에 publish 하려면 명시적으로 허용해야 함
resource "aws_sns_topic_policy" "budget_alerts" {
  arn    = aws_sns_topic.budget_alerts.arn
  policy = data.aws_iam_policy_document.budget_alerts.json
}

data "aws_iam_policy_document" "budget_alerts" {
  statement {
    effect  = "Allow"
    actions = ["SNS:Publish"]

    principals {
      type        = "Service"
      identifiers = ["budgets.amazonaws.com"]
    }

    resources = [aws_sns_topic.budget_alerts.arn]
  }
}

resource "aws_sns_topic_subscription" "budget_email" {
  topic_arn = aws_sns_topic.budget_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# 한도를 $50 이 아니라 $10 으로 잡은 이유:
# 상시 서비스가 아니라 검증용 단기 기동이라, $50 은 이 운용에서 사실상 울리지 않는
# 임계값이다. 목적이 상한 방어가 아니라 이상 징후 조기 감지이므로 낮게 잡는다.
# (콘솔에서 만든 기존 예산 2개 - My Monthly Cost Budget / My Zero-Spend Budget - 이
#  Terraform 밖에 남아 있어 알림이 중복될 수 있다. 의도적으로 그대로 둔다.)
resource "aws_budgets_budget" "monthly" {
  name         = "${var.project}-${var.environment}-monthly"
  budget_type  = "COST"
  limit_amount = "10"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # 실제 사용량이 $10을 넘으면
  notification {
    comparison_operator = "GREATER_THAN"
    threshold           = 100
    threshold_type      = "PERCENTAGE"
    # 이미 쓴 돈. 알았을 땐 이미 나간 돈
    notification_type         = "ACTUAL"
    subscriber_sns_topic_arns = [aws_sns_topic.budget_alerts.arn]
  }

  notification {
    comparison_operator = "GREATER_THAN"
    threshold           = 150
    threshold_type      = "PERCENTAGE"
    # AWS가 추세로 예측한 월말 금액, 빠르지만 부정확
    notification_type         = "FORECASTED"
    subscriber_sns_topic_arns = [aws_sns_topic.budget_alerts.arn]
  }
}
