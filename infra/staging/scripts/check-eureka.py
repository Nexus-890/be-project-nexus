#!/usr/bin/env python3
import json
import sys

response = sys.stdin.read()
try:
    # The Bash TCP client receives HTTP headers as well as the body.
    body = response[response.index('{'):]
    apps = json.loads(body)["applications"]["application"]
    if isinstance(apps, dict):
        apps = [apps]
    expected = {name.upper() for name in ["api-gateway", "user-service", "catalog-service", "commerce-service", "auction-service", "inventory-service", "notification-service", "payment-service", "shipment-service", "rating-service"]}
    up = set()
    for app in apps:
        instances = app["instance"]
        if isinstance(instances, dict):
            instances = [instances]
        if any(instance["status"] == "UP" for instance in instances):
            up.add(app["name"])
    missing = expected - up
    if missing:
        sys.exit("Waiting for Eureka UP: " + ", ".join(sorted(missing)))
except (ValueError, KeyError, TypeError) as error:
    sys.exit(f"Waiting for a complete Eureka registry: {type(error).__name__}")
print("All nine domain services and Gateway are UP in Eureka")
