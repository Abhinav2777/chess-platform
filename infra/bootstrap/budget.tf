# The cost alarm. First in the stack and first in DEPLOYMENT.md, because every other mistake in
# this project is survivable if someone hears about it within a day.
#
# This budget was created by hand before any Terraform existed (the right order), so it
# is adopted with `terraform import`, not recreated. The name is part of the budget's identity:
# changing it replaces the budget.
resource "aws_budgets_budget" "monthly" {
  name         = var.budget_name
  budget_type  = "COST"
  limit_amount = format("%.1f", var.budget_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Gross usage, credits and refunds excluded — kept exactly as the console created it. With
  # credits counted, an account on promotional credit reads ~$0 while a forgotten stack spends
  # real credit, and the alarm never fires. (The first plan after import wanted to drop this
  # filter; caught in plan review, 7.2.)
  metrics          = ["UnblendedCost"]
  billing_view_arn = "arn:aws:billing::${data.aws_caller_identity.current.account_id}:billingview/primary"

  filter_expression {
    not {
      dimensions {
        key    = "RECORD_TYPE"
        values = ["Credit", "Refund"]
      }
    }
  }

  # Early warning: 85 % of actual spend.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 85
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = var.budget_alert_emails
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = var.budget_alert_emails
  }

  # The one that catches a forgotten `apply`: AWS projects the month from the current run
  # rate, so an ECS stack left up for two days trips this long before actual spend does.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = var.budget_alert_emails
  }
}
