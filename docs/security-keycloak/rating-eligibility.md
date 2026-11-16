# Rating eligibility machine authentication (#212 / #213)

`GET /internal/v1/eligibility/{userId}?action=BID|SELL` requires a Keycloak service-account token. The rating service verifies the token with the shared RS256 decoder, then `EligibilityAuthorization` requires all of:

- A verified, authenticated `JwtAuthenticationToken` with the configured issuer and `nexus-services` audience.
- `azp` in `nexus.rating.eligibility.allowed-clients`, default `nexus-auction-service`.
- The JSON boolean claim `service_account: true`.
- The exact `RATING.ELIGIBILITY.CHECK` authority from the flat `privileges` claim.

Buyer, Seller, Admin and Support tokens do not gain internal access from their roles or an end-user privilege alone. The gateway does not expose `/internal/v1`; the service enforces the same check when called directly.

## Provision the caller

The secret-free client definition is [auction-rating-client.json](../../infra/local-dev/keycloak/auction-rating-client.json). It allows client credentials through a confidential service account, disables browser/password/implicit/device/CIBA/token-exchange flows, and emits only the internal eligibility privilege, machine marker and service audience. `privileges` is a JSON array and `service_account` is a boolean; changing either to a string makes authorization fail.

Set `AUCTION_RATING_CLIENT_SECRET` to a generated secret in the untracked `infra/local-dev/.env`, then run the existing bootstrap command from `infra/local-dev`:

```powershell
.\keycloak\seed-users.ps1
```

The command requires the existing administrator/demo/user-service settings described in [the Keycloak setup guide](../../infra/local-dev/keycloak/README.md). It applies the configured demo passwords as usual, then creates or updates `nexus-auction-service` and sets its secret from the environment. Re-running applies the committed mapper/grant restrictions to an existing client. It grants no `realm-management` roles to this service account.

An empty `AUCTION_RATING_CLIENT_SECRET` skips this optional client, so ordinary local setup and enforcement OFF do not require an auction machine credential. A skipped seed does not delete or rotate an already provisioned client. Revoke or disable a previously provisioned client explicitly when retiring it.

The client is provisioned by the seed command rather than realm startup import, so the same procedure works for an existing realm. Committing these files or restarting services does not update a running Keycloak deployment. Use its normal provisioning and secret-management procedure outside local development. No real deployment or credentials were changed by this implementation.

## Configure and check REP-10

`RATING_ELIGIBILITY_ALLOWED_CLIENTS` configures the rating service's comma-separated allowlist. Its default is `nexus-auction-service`; an empty allowlist denies every caller. Supply the value to the rating process or Compose rating profile. Keep `KEYCLOAK_ISSUER_URI` and `KEYCLOAK_JWK_SET_URI` aligned with the realm issuing the token.

The caller uses client ID `nexus-auction-service`, `grant_type=client_credentials`, and the matching externally supplied `AUCTION_RATING_CLIENT_SECRET` against the realm token endpoint. The following manual example assumes that the secret is already set in the current PowerShell environment; `.env` does not populate a shell's environment by itself:

```powershell
$keycloakBase = 'http://localhost:18080'
$machineToken = Invoke-RestMethod -Method Post `
  -Uri "$keycloakBase/realms/nexus/protocol/openid-connect/token" `
  -Body @{grant_type='client_credentials'; client_id='nexus-auction-service'; client_secret=$env:AUCTION_RATING_CLIENT_SECRET}

Invoke-RestMethod `
  -Uri 'http://localhost:8089/internal/v1/eligibility/68000000-0000-4000-8000-000000000003?action=BID' `
  -Headers @{Authorization="Bearer $($machineToken.access_token)"}
```

Check the decision in `data.eligible` and its reason; a negative decision is HTTP 200. Missing/invalid token returns 401. A valid token with a wrong client, marker or authority returns 403. Keep secrets and access tokens out of command output, issue evidence and source control.

`EligibilityAuthorizationSecurityTest` uses signed tokens and a local JWKS server to exercise the actual decoder, filter chain and method authorization without a real Keycloak server. It covers service-only access, human roles, wrong issuer/audience/signature, expiry and forged identity headers. `keycloak/test-realm.py` validates the provisioning template and optional seed wiring offline. These checks do not prove an existing deployment has been provisioned; confirm client credentials and REP-10 against that deployment before enabling enforcement.
