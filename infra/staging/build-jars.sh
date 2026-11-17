#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERVICES=(config-service discovery-service api-gateway user-service catalog-service commerce-service
          inventory-service auction-service notification-service payment-service shipment-service rating-service)

mvn -B -q -f "$ROOT/lib/pom.xml" -Dmaven.test.skip=true install
for service in "${SERVICES[@]}"; do
  echo "packaging $service"
  mvn -B -q -f "$ROOT/$service/pom.xml" -Dmaven.test.skip=true clean package
done
ls -1 "$ROOT"/*/target/*.jar
