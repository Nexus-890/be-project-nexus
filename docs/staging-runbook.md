# STAGING runbook

Status: written against `infra/staging/` before a STAGING VM exists. Each command below was
validated locally (compose syntax, image builds, start-up of the platform services), not on the
VM. Run them on STAGING, fix what differs, and remove this line.

All commands run on the VM from `be-project-nexus/infra/staging`, as a user in the `docker`
group. Only ports 80 and 443 are published; Zipkin and Mailpit listen on `127.0.0.1` and are
reached through an SSH tunnel.

## 1. Prepare the VM once

Needs: Docker Engine with the compose plugin, git, 60 GB disk, and a DNS name pointing at the VM.
Open TCP 80 and 443 in the cloud firewall and nothing else besides SSH.

```bash
git clone https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5.git nexus
cd nexus/be-project-nexus/infra/staging
cp .env.example .env
chmod 600 .env
```

Fill every `<change-me>` in `.env`. Generate each secret with `openssl rand -base64 36`.
`STAGING_HOST` is the DNS name, `NEXUS_TAG` the short SHA you are about to deploy.

## 2. Issue the first certificate

nginx cannot start without a certificate, so the first one is issued with certbot's own web
server on port 80, before nginx runs:

```bash
set -a; . ./.env; set +a
docker compose --profile tools run --rm -p 80:80 certbot certonly --standalone \
  -d "$STAGING_HOST" -m "$LETSENCRYPT_EMAIL" --agree-tos -n
```

From then on `certbot-renew` renews through nginx's `/.well-known/acme-challenge/` every 12 hours
and nginx reloads every 6 hours, so a renewed certificate is served without a restart.
Check renewal without changing anything: `docker compose exec certbot-renew certbot renew --dry-run --webroot -w /var/www/certbot`.

## 3. Deploy a commit

```bash
cd ~/nexus && git fetch origin && git checkout <commit>
cd be-project-nexus
docker run --rm -v "$PWD":/src -v nexus-m2:/root/.m2 -w /src maven:3.9.11-eclipse-temurin-21 \
  bash infra/staging/build-jars.sh
cd infra/staging
sed -i "s/^NEXUS_TAG=.*/NEXUS_TAG=$(git rev-parse --short HEAD)/" .env
docker compose build
docker compose up -d
```

The first deploy also seeds Keycloak (client secrets, demo passwords, the public redirect URI):

```bash
docker compose --profile tools run --rm auth-seed
```

Check:

```bash
docker compose ps
curl -s http://127.0.0.1/.nginx-health
curl -sk https://$STAGING_HOST/api/v1/products?size=3
```

Every service should be `healthy`. Services start in order: config, discovery, then the rest.
On a 2 vCPU VM the whole stack takes 3 to 5 minutes from a cold start.

## 4. Logs and traces

```bash
docker compose logs -f --tail=200 user-service
docker compose logs --since=10m commerce-service payment-service | grep <traceId>
```

Spring Boot writes the trace and span id of the current request into each log line, so a grep on
one trace id finds the same request in every service. To see the whole trace, open Zipkin through
a tunnel from your laptop and search the trace id:

```bash
ssh -L 9411:127.0.0.1:9411 -L 8025:127.0.0.1:8025 <user>@<vm>
```

Then open `http://localhost:9411` (Zipkin) and `http://localhost:8025` (Mailpit).

## 5. Roll back

Images are tagged with the commit SHA, so the previous release is still on the VM:

```bash
docker image ls nexus/user-service
sed -i "s/^NEXUS_TAG=.*/NEXUS_TAG=<previous-sha>/" .env
docker compose up -d --no-build
```

This changes only the application containers; the database volumes stay as they are. Flyway
migrations are forward only: if the release being rolled back added a migration, the old code
runs on the newer schema. Restore the backup taken before the deploy (section 6) when the old
code cannot run on it.

## 6. Back up and restore

Take a backup before every deploy:

```bash
bash backup.sh
```

It writes one `pg_dump -Fc` file per database (`user_db`, `catalog_db`, `commerce_db`,
`auction_db`, `inventory_db`, `notification_db`, `payment_db`, `shipment_db`, `rating_db`,
`auth_db`) under `backups/<timestamp>/`. Copy that folder off the VM.

Restore one database into an empty one, then check it:

```bash
set -a; . ./.env; set +a
docker compose exec -T postgres createdb -U "$DB_USERNAME" restore_check
docker compose exec -T postgres pg_restore -U "$DB_USERNAME" -d restore_check --no-owner < backups/<timestamp>/catalog_db.dump
docker compose exec -T postgres psql -U "$DB_USERNAME" -d restore_check -c "select count(*) from products"
docker compose exec -T postgres dropdb -U "$DB_USERNAME" restore_check
```

To replace a live database, stop its service first, drop and recreate the database, restore,
then start the service again.

## 7. Restart and stop

```bash
docker compose restart
docker compose stop
docker compose down
```

`down` keeps the volumes. `down -v` deletes every database, the Kafka log and the certificates;
do not use it on STAGING.
