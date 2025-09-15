#!/usr/bin/env bash
set -euo pipefail
mvn -q -e -DskipTests package
mkdir -p build
cp target/spending-insights-backend-0.1.0-shaded.jar build/app.jar
cd build
zip -r lambda.zip app.jar >/dev/null
echo "Built build/lambda.zip"
