resource "random_id" "rand" {
  byte_length = 3
}

resource "aws_s3_bucket" "site" {
  bucket = "${var.project_name}-site-${random_id.rand.hex}"
}

# Keep the bucket private and block public policies
resource "aws_s3_bucket_public_access_block" "site" {
  bucket                  = aws_s3_bucket.site.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}
