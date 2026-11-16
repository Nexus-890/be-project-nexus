# STAGING capacity

Status: **not measured**. No VM handover, purchase warm-up or auction warm-up
has been verified. Container limits are an initial budget, not measurements.

The current source needs nine domain JVMs, Config, Eureka, Gateway, Keycloak,
six PostgreSQL instances containing ten logical databases, Kafka, Redis,
Zipkin, Next.js and nginx. The original issue's six-database wording predates
Payment, Shipment, Rating and the Keycloak realm database.

| Required evidence | Status |
| --- | --- |
| VM hostname, owner, access custodian, RAM/disk, payer, expiry | Awaiting confirmed handover |
| Completed purchase and auction warm-up | Not run on STAGING |
| `docker stats --no-stream`, one row per running container | Not run on STAGING |
| Host RAM and disk used/free | Not measured |
| At least 20% memory headroom, or same-day escalation | Not measured |
| Three largest containers and justified heap adjustment | Not measured |

After warming the actual VM, from `be-project-nexus/infra/staging`:

```bash
bash scripts/staging.sh capacity --purchase ORDER_ID --auction AUCTION_ID
free -h
df -h
```

The recorder replaces this pending page with actual Docker measurements,
the warm-up references, RAM/headroom, disk and three largest containers.
It also preserves raw stats under the ignored `evidence/` directory. Inspect
OS memory and GC/OOM logs and record any heap adjustment alongside the table.
If below 20%, it exits with failure and asks for same-day escalation. A local
measurement or a configured memory limit does not meet the STAGING criteria.
