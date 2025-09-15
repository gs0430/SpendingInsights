# Spending Insights (Lite++) — Starter Kit

Stack:
- **Frontend:** React + TypeScript (Vite). CSV parsing in-browser; charts; budgets & rules UI.
- **Backend:** Java 17 on AWS Lambda (HTTP API via API Gateway). Minimal GET/POST endpoints.
- **DB:** Neon (serverless Postgres) for budgets & rules persistence.
- **Infra:** Terraform (S3 static hosting + API Gateway + Lambda + IAM). **Note:** This starter publishes the frontend to S3 website hosting for simplicity; you can add CloudFront later.

## Quick Start

### 1) Database (Neon)
1. Create a Neon serverless Postgres database.
2. Get your connection info and construct a JDBC URL (example):  
   `jdbc:postgresql://<host>/<db>?sslmode=require`
3. Run the schema in `sql/schema.sql`.

### 2) Backend (Java Lambda)
```bash
cd backend
./build.sh   # builds build/lambda.zip
```
Environment variables needed for the Lambda:
- `JDBC_URL`
- `DB_USER`
- `DB_PASSWORD`
- `ALLOWED_ORIGINS` (e.g., `*` OR your S3 website endpoint)

### 3) Infra (Terraform)
```bash
cd infra/terraform
terraform init
terraform apply -auto-approve
# outputs will include: api_base_url and s3_website_endpoint
```
After `apply`, update the frontend `.env` file with:
```
VITE_API_BASE_URL=<api_base_url_from_terraform>
```

### 4) Frontend
```bash
cd frontend
npm install
npm run build
# Upload dist/ to the S3 website bucket (see terraform output bucket name), e.g.:
aws s3 sync dist/ s3://<your-bucket-name> --delete
```

### 5) Test
- Open the S3 website endpoint in your browser.
- Use the "Test API" button on the page to verify `/health` and `/v1/budgets`.

### CloudFront (optional, later)
Add a CloudFront distribution in Terraform and point it at the S3 bucket (use OAI/OAC).

### Clean-up
```bash
cd infra/terraform
terraform destroy -auto-approve
```

