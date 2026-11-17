# STAGING capacity

Status: no STAGING VM yet. The figures below are a baseline measured on a laptop on 2026-11-17
from `develop` (`89e26477`), so the VM size can be chosen before it is requested. Replace them with
`docker stats --no-stream` from the warm STAGING stack (one purchase and one auction done) and
keep the baseline for comparison.

## Baseline: resident memory per process, warm, light traffic

Each service ran as a plain JVM with `-Xmx256m` (`compose.yaml` allows 256 to 384 MB per
service), after 40 requests through the gateway and a 60 second pause. Figures are the Windows
working set; Linux RSS in a container is usually equal or a little lower.

| Container | MB |
| --- | ---: |
| kafka (long-running, local-dev) | 835 |
| auth-service (Keycloak) | 622 |
| commerce-service | 473 |
| notification-service | 472 |
| auction-service | 466 |
| inventory-service | 453 |
| catalog-service | 434 |
| shipment-service | 430 |
| user-service | 416 |
| payment-service | 416 |
| rating-service | 405 |
| api-gateway | 285 |
| postgres (10 databases, migrated) | 269 |
| discovery-service | 250 |
| config-service | 216 |
| zipkin | 285 |
| web (Next.js, estimate) | 200 |
| mailpit, redis, nginx (estimate) | 50 |
| **Total** | **about 7,000** |

The three largest containers are kafka, auth-service and commerce-service.

## Headroom

| VM RAM | Headroom against about 7 GB | Verdict |
| --- | ---: | --- |
| 8 GB | about 12 % | Below the 20 % the issue asks for; heaps grow toward their limits under load |
| 12 GB (Oracle Always Free A1) | about 42 % | Enough |
| 16 GB (issue budget) | about 56 % | Comfortable |

The `mem_limit` values in `compose.yaml` add up to about 12.5 GB. They are ceilings that stop one
container from taking the whole VM, not reservations, so a 12 GB VM still fits the measured total.

## On STAGING

```bash
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'
df -h /
docker system df
```

Record the table above with the STAGING figures, disk used and free, the total against the VM's
RAM, and any heap changed because a container sat at its limit or far below it.
