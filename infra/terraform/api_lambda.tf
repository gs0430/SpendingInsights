resource "aws_lambda_function" "api" {
  function_name = "${var.project_name}-api"
  role          = aws_iam_role.lambda_exec.arn
  handler       = "app.ApiHandler::handleRequest"
  runtime       = "java17"
  memory_size   = 256
  timeout       = 10

  filename         = var.lambda_zip_path
  source_code_hash = filebase64sha256(var.lambda_zip_path)

  environment {
    variables = {
      JDBC_URL        = var.jdbc_url
      DB_USER         = var.db_user
      DB_PASSWORD     = var.db_pass
      ALLOWED_ORIGINS = var.allowed_origins
    }
  }
}

resource "aws_apigatewayv2_api" "api" {
  name          = "${var.project_name}-http"
  protocol_type = "HTTP"
  cors_configuration {
    allow_origins = split(",", var.allowed_origins)
    allow_methods = ["GET", "POST", "OPTIONS"]
    allow_headers = ["content-type", "authorization"]
  }
}

resource "aws_apigatewayv2_integration" "lambda_integ" {
  api_id                 = aws_apigatewayv2_api.api.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.api.arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "health" {
  api_id    = aws_apigatewayv2_api.api.id
  route_key = "GET /health"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_integ.id}"
}

resource "aws_apigatewayv2_route" "get_budgets" {
  api_id    = aws_apigatewayv2_api.api.id
  route_key = "GET /v1/budgets"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_integ.id}"
}

resource "aws_apigatewayv2_route" "post_budgets" {
  api_id    = aws_apigatewayv2_api.api.id
  route_key = "POST /v1/budgets"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_integ.id}"
}

resource "aws_apigatewayv2_route" "post_spend" {
  api_id             = aws_apigatewayv2_api.api.id
  route_key          = "POST /v1/spend"
  authorization_type = "NONE"
  target             = "integrations/${aws_apigatewayv2_integration.lambda_integ.id}"
}

resource "aws_apigatewayv2_route" "get_insights" {
  api_id             = aws_apigatewayv2_api.api.id
  route_key          = "GET /v1/insights"
  authorization_type = "NONE"
  target             = "integrations/${aws_apigatewayv2_integration.lambda_integ.id}"
}

resource "aws_lambda_permission" "allow_apigw" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.api.execution_arn}/*/*"
}

resource "aws_apigatewayv2_stage" "api" {
  api_id      = aws_apigatewayv2_api.api.id
  name        = "prod"
  auto_deploy = true
}
