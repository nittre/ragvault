# Route53 — 고객사 서브도메인 + ACM 인증서 DNS 검증
# ADR-0003: ALB Multi-AZ 의무

# ── ACM 인증서 ─────────────────────────────────────────────────────────────────
resource "aws_acm_certificate" "main" {
  domain_name       = var.ses_sender_domain
  validation_method = "DNS"

  # SAN: www 서브도메인도 커버
  subject_alternative_names = ["www.${var.ses_sender_domain}"]

  lifecycle {
    create_before_destroy = true
  }

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-acm" })
}

# ── ACM DNS 검증 레코드 ────────────────────────────────────────────────────────
resource "aws_route53_record" "acm_validation" {
  for_each = {
    for dvo in aws_acm_certificate.main.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  }

  zone_id = var.route53_zone_id
  name    = each.value.name
  type    = each.value.type
  ttl     = 300
  records = [each.value.record]

  allow_overwrite = true
}

# ── ACM 검증 완료 대기 ─────────────────────────────────────────────────────────
resource "aws_acm_certificate_validation" "main" {
  certificate_arn         = aws_acm_certificate.main.arn
  validation_record_fqdns = [for record in aws_route53_record.acm_validation : record.fqdn]
}

# ── 서브도메인 A 레코드 → ALB alias ───────────────────────────────────────────
resource "aws_route53_record" "app" {
  zone_id = var.route53_zone_id
  name    = var.ses_sender_domain
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}
