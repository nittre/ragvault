# CloudWatch 알람 — Critical / Warning / Info 3채널 Discord 알림
# requirements/06-error-handling.md 섹션 9 참조

# ── SNS Topics (Discord Lambda 트리거) ────────────────────────────────────
resource "aws_sns_topic" "critical" {
  name = "${local.name_prefix}-alerts-critical"
  tags = local.common_tags
}

resource "aws_sns_topic" "warning" {
  name = "${local.name_prefix}-alerts-warning"
  tags = local.common_tags
}

resource "aws_sns_topic" "info" {
  name = "${local.name_prefix}-alerts-info"
  tags = local.common_tags
}

# Discord Webhook URL 은 Secrets Manager 에서 주입
# Lambda 함수로 SNS → Discord Webhook 전달 (별도 배포)
resource "aws_sns_topic_subscription" "critical_discord" {
  topic_arn = aws_sns_topic.critical.arn
  protocol  = "https"
  endpoint  = var.discord_critical_webhook_url
}

resource "aws_sns_topic_subscription" "warning_discord" {
  topic_arn = aws_sns_topic.warning.arn
  protocol  = "https"
  endpoint  = var.discord_warning_webhook_url
}

# ── ALB 알람 ─────────────────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "alb_5xx_critical" {
  alarm_name          = "${local.name_prefix}-alb-5xx-critical"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "HTTPCode_Target_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Sum"
  threshold           = 10
  alarm_description   = "ALB 5xx 에러 10건/분 초과 — 즉시 확인 필요"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }

  alarm_actions = [aws_sns_topic.critical.arn]
  ok_actions    = [aws_sns_topic.info.arn]
  tags          = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "alb_latency_warning" {
  alarm_name          = "${local.name_prefix}-alb-latency-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "TargetResponseTime"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  extended_statistic  = "p99"
  threshold           = 30  # 30s (LLM response — high threshold intentional)
  alarm_description   = "ALB p99 응답시간 30초 초과"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }

  alarm_actions = [aws_sns_topic.warning.arn]
  tags          = local.common_tags
}

# ── RDS 알람 ─────────────────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "rds_cpu_warning" {
  alarm_name          = "${local.name_prefix}-rds-cpu-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "RDS CPU 80% 초과"
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.id
  }

  alarm_actions = [aws_sns_topic.warning.arn]
  tags          = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "rds_storage_critical" {
  alarm_name          = "${local.name_prefix}-rds-storage-critical"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 5368709120  # 5GB
  alarm_description   = "RDS 남은 스토리지 5GB 미만 — 즉시 확인"
  treat_missing_data  = "breaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.id
  }

  alarm_actions = [aws_sns_topic.critical.arn]
  tags          = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "rds_connections_warning" {
  alarm_name          = "${local.name_prefix}-rds-connections-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "DatabaseConnections"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "RDS 커넥션 수 80개 초과"
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.id
  }

  alarm_actions = [aws_sns_topic.warning.arn]
  tags          = local.common_tags
}

# ── EC2 (App 노드) 알람 ───────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "app_cpu_warning" {
  count               = var.ec2_app_count
  alarm_name          = "${local.name_prefix}-app-${count.index + 1}-cpu-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = 300
  statistic           = "Average"
  threshold           = 85
  alarm_description   = "App 노드 ${count.index + 1} CPU 85% 초과"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = aws_instance.app[count.index].id
  }

  alarm_actions = [aws_sns_topic.warning.arn]
  tags          = local.common_tags
}

# ── ALB Target Healthy Host ──────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "alb_healthy_hosts_critical" {
  alarm_name          = "${local.name_prefix}-alb-healthy-hosts-critical"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "HealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Average"
  threshold           = 1
  alarm_description   = "정상 App 인스턴스 0개 — 서비스 중단"
  treat_missing_data  = "breaching"

  dimensions = {
    TargetGroup  = aws_lb_target_group.app.arn_suffix
    LoadBalancer = aws_lb.main.arn_suffix
  }

  alarm_actions = [aws_sns_topic.critical.arn]
  tags          = local.common_tags
}
