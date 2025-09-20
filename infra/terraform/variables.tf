variable "region" {
  type    = string
  default = "us-east-1"
}

variable "project_name" {
  type    = string
  default = "spending-insights"
}

variable "lambda_zip_path" {
  description = "Path to the built lambda.zip"
  type        = string
  default     = "../../backend/build/lambda.zip"
}

variable "jdbc_url" { type = string }
variable "db_user"  { type = string }
variable "db_pass"  { type = string }
variable "allowed_origins" {
  type    = string
  default = "*"
}

variable "plaid_client_id" {
  type = string
}

variable "plaid_secret" {
  type = string
}

variable "plaid_env" {
  type    = string
  default = "SANDBOX"
}

variable "plaid_webhook_url" {
  type    = string
  default = ""
}
