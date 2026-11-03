# Notification read state — #109

Prerequisite #108 (notification list and unread count) is closed and its controller,
service and repository are present in the develop baseline used by this branch.

## Behavior

- `PATCH /api/v1/notifications/{id}/read`: owner only; 204 without a body.
  Another owner gets 403 `NOTIFICATION_NOT_OWNER`; missing ID gets 404
  `NOTIFICATION_NOT_FOUND`. Repeating an already-read notification changes no columns.
- `PATCH /api/v1/notifications/read-all`: one SQL UPDATE of the JWT subject's
  unread rows, returning 204 even if none match. Other users and already-read rows
  remain untouched. Bulk queries explicitly record `updated_by` because JPA entity
  auditing callbacks do not run for bulk updates.
- Both gateway paths require a bearer token and `NOTIFICATION.VIEW`, matching
  the existing notification reads and the documented permission matrix. The service
  checks the same privilege and takes ownership from `CurrentUser`, never a caller-
  supplied user ID.

## Verification on 2026-10-30

Using Java 21, JVM timezone UTC and isolated PostgreSQL 18 test databases:

- notification-service `mvnw.cmd -Duser.timezone=UTC verify`: **41 tests passed**,
  including **8 NotificationMarkReadIntegrationTest** cases. Spring Boot starts with
  real security filters, method authorization, service, Flyway and JPA validation.
  JWT decoding is stubbed only in this integration fixture; ownership and SQL are real.
- api-gateway `mvnw.cmd -Duser.timezone=UTC verify`: **41 tests passed**, including
  gateway checks using actual RS256-signed JWTs and local JWKS/downstream fixtures.
- `node docs/api/check-contract.mjs`: **34 delivered operations match**.
- Redocly CLI 2.63.0 lint of `docs/api/openapi/notification-service.yaml`: passed.

The DB tests compare all columns before/after a forbidden request, preserve read
timestamps on repeated calls, verify user isolation, check unread-count after a
three-row read-all, and assert exactly one prepared statement for read-all.

## Rerun

Start Docker Desktop (Linux containers), install shared libraries from
`be-project-nexus/lib` with `mvnw.cmd install -DskipTests`, then run the service and
gateway verify commands above from their respective directories. Run the contract
checker and pinned Redocly lint from `be-project-nexus`.
