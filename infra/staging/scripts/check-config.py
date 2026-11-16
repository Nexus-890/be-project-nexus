#!/usr/bin/env python3
"""Validate the resolved Compose JSON on stdin without printing host secrets."""
import json
import base64
import re
import sys

config = json.load(sys.stdin)
services = config["services"]
domain_services = ["user", "catalog", "commerce", "auction", "inventory", "notification", "payment", "shipment", "rating"]
java_services = ["config-service", "discovery-service", "api-gateway"] + [f"{name}-service" for name in domain_services]
errors = []
for name in java_services:
    service = services.get(name, {})
    if not re.search(r"-Xmx\d+[mMgG]", service.get("environment", {}).get("JAVA_TOOL_OPTIONS", "")):
        errors.append(f"{name}: explicit maximum JVM heap missing")
    if not service.get("healthcheck") or not service.get("mem_limit"):
        errors.append(f"{name}: healthcheck/container memory bound missing")
for name, service in services.items():
    if name != "nginx" and service.get("ports"):
        errors.append(f"{name}: internal ports must not be published")
    if "debug" in service.get("profiles", []) or "kafka-ui" in name:
        errors.append(f"{name}: development service/profile present")
    for key, value in service.get("environment", {}).items():
        if re.search(r"PASSWORD|SECRET|ENCRYPTION_KEY", key) and not value:
            optional = {"MAIL_PASSWORD", "SPRING_MAIL_PASSWORD", "GHN_WEBHOOK_SECRET", "SUPABASE_SERVICE_KEY"}
            if key not in optional:
                errors.append(f"{name}: {key} is empty")
        if value and re.search(r"(?i)^(change[-_]?me|replace[-_]?me|your[-_])", str(value)):
            errors.append(f"{name}: {key} still contains a placeholder")
    if "host.docker.internal" in json.dumps(service):
        errors.append(f"{name}: laptop-only hostname present")
if len([name for name in services if name.startswith("postgres-")]) != 6:
    errors.append("exactly six PostgreSQL instances are required")
gateway_env = services["api-gateway"]["environment"]
if gateway_env.get("SERVER_FORWARD_HEADERS_STRATEGY") != "framework":
    errors.append("gateway must process nginx's overwritten forwarding headers")
try:
    encryption = services["payment-service"]["environment"]["PAYOUT_ACCOUNT_ENCRYPTION_KEY"]
    if len(base64.b64decode(encryption, validate=True)) != 32:
        errors.append("PAYOUT_ACCOUNT_ENCRYPTION_KEY must decode to 32 bytes")
except (ValueError, KeyError):
    errors.append("PAYOUT_ACCOUNT_ENCRYPTION_KEY must be base64 for 32 bytes")
shipping = services["shipment-service"]["environment"]
if shipping.get("SHIPPING_CARRIER", "NONE").upper() == "GHN":
    for key in ["GHN_TOKEN", "GHN_WEBHOOK_SECRET", "SHIPPING_DEFAULT_WAREHOUSE_ID", "SHIPPING_PICKUP_PROVINCE_CODE", "SHIPPING_PICKUP_WARD_CODE", "SHIPPING_PICKUP_ADDRESS_LINE"]:
        if not shipping.get(key):
            errors.append(f"GHN mode requires {key}")
    if not shipping.get("GHN_SHOP_ID", "0").isdigit() or int(shipping.get("GHN_SHOP_ID", "0")) <= 0:
        errors.append("GHN mode requires a positive GHN_SHOP_ID")
domain = services["nginx"]["environment"]["DOMAIN"]
if not re.fullmatch(r"(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?", domain) or "." not in domain:
    errors.append("DOMAIN must be a DNS hostname, without scheme/path/port")
if errors:
    sys.exit("\n".join(errors))
print(f"Compose checks passed: {len(java_services)} JVM apps, six PostgreSQL instances; only nginx publishes ports.")
