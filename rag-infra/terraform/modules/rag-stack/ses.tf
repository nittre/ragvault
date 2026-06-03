# SES — 트랜잭션 메일 (비밀번호 재설정, 알림 등)
# 로컬/개발: SMTP 비활성화
# 상용: Open WebUI SMTP 설정 → SES SMTP 엔드포인트

resource "aws_ses_domain_identity" "main" {
  domain = var.ses_sender_domain
}

resource "aws_ses_domain_dkim" "main" {
  domain = aws_ses_domain_identity.main.domain
}

# Route53 DKIM CNAME 레코드 (SES 도메인 검증)
resource "aws_route53_record" "dkim" {
  count   = 3
  zone_id = var.route53_zone_id
  name    = "${aws_ses_domain_dkim.main.dkim_tokens[count.index]}._domainkey.${var.ses_sender_domain}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_ses_domain_dkim.main.dkim_tokens[count.index]}.dkim.amazonses.com"]
}

# SES Mail From 도메인 설정
resource "aws_ses_domain_mail_from" "main" {
  domain           = aws_ses_domain_identity.main.domain
  mail_from_domain = "mail.${var.ses_sender_domain}"
}

# Mail From MX 레코드
resource "aws_route53_record" "ses_mx" {
  zone_id = var.route53_zone_id
  name    = "mail.${var.ses_sender_domain}"
  type    = "MX"
  ttl     = 600
  records = ["10 feedback-smtp.${var.aws_region}.amazonses.com"]
}

# Mail From SPF TXT 레코드
resource "aws_route53_record" "ses_spf" {
  zone_id = var.route53_zone_id
  name    = "mail.${var.ses_sender_domain}"
  type    = "TXT"
  ttl     = 600
  records = ["v=spf1 include:amazonses.com ~all"]
}
