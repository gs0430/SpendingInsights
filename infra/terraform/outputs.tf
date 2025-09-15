output "api_base_url" {
  value = aws_apigatewayv2_stage.api.invoke_url
}

output "site_bucket_name" {
  value = aws_s3_bucket.site.id
}

output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.site.domain_name
}
