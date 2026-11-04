# Outbound email through Amazon SES (the workflow service sends replies to prospects).
#
# The sender address is created as an SES identity, which makes AWS send it a verification email: open the link in it once. A new
# SES account is in the sandbox, which only delivers to verified addresses, so for now every email is redirected to var.email_override_to
# (the same address is verified here too). To send to real prospects: ask AWS for production access, verify a domain, then set
# email_override_to to "" and email_from to an address on that domain.

resource "aws_sesv2_email_identity" "sender" {
  count          = var.email_provider == "ses" ? 1 : 0
  email_identity = var.email_from
}

# The recipient of redirected emails must be verified while the account is in the sandbox (skipped when it is the sender itself).
resource "aws_sesv2_email_identity" "override" {
  count          = var.email_provider == "ses" && var.email_override_to != "" && var.email_override_to != var.email_from ? 1 : 0
  email_identity = var.email_override_to
}

resource "aws_iam_role_policy" "task_ses" {
  count = var.email_provider == "ses" ? 1 : 0
  name  = "ses-send"
  role  = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ses:SendEmail"]
      Resource = concat([aws_sesv2_email_identity.sender[0].arn], [for i in aws_sesv2_email_identity.override : i.arn])
    }]
  })
}
