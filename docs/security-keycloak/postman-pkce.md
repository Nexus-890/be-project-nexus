# Get a Nexus access token with Postman (OAuth 2.0 + PKCE)

Use the Postman desktop app and the local Keycloak `nexus` realm supplied by
**!72 / #236**. Start and seed it using that MR's
`infra/local-dev/keycloak/README.md`. Gateway and services must run with !78's
Keycloak issuer/JWKS configuration. Password/direct grants are disabled for the
public `nexus-web` client; do not use `grant_type=password` or the old
`/api/v1/auth/login` endpoint to obtain a token for protected APIs.

## 1. Register the Postman callback in your local realm

The committed client allows only `http://localhost:3000/*`. Postman's browser
callback is a different URI and will be rejected until explicitly registered:

1. Open `http://localhost:18080/admin` (adjust for `KEYCLOAK_PORT`), sign in with
   your local bootstrap admin, and select realm **nexus**.
2. Open **Clients > nexus-web > Settings > Access settings**.
3. Add this exact **Valid redirect URI**, preserving `http://localhost:3000/*`:
   `https://oauth.pstmn.io/v1/browser-callback`. Save.
4. Keep the client public (**Client authentication: Off**), Standard flow enabled,
   and PKCE challenge method **S256**. Keep Direct access grants disabled.

This is an explicit local development setting for manual Postman checks. Do not
add wildcard redirect URIs or broaden Web origins. No extra Web origin is needed
for the Postman desktop client's token request. Do not add this callback to a
production realm just to run local tests. If you recreate `auth_db`, repeat this
registration; `--import-realm` will not update a realm that already exists.

## 2. Configure OAuth 2.0 in Postman

In the collection's **Authorization** tab, select **OAuth 2.0**, add auth data to
**Request Headers**, then configure a new token:

| Field | Local value |
|---|---|
| Token Name | `Nexus seller PKCE` |
| Grant Type | `Authorization Code (With PKCE)` |
| Callback URL | `https://oauth.pstmn.io/v1/browser-callback` |
| Authorize using browser | Enabled; confirm callback still matches the URI above |
| Auth URL | `http://localhost:18080/realms/nexus/protocol/openid-connect/auth` |
| Access Token URL | `http://localhost:18080/realms/nexus/protocol/openid-connect/token` |
| Client ID | `nexus-web` |
| Client Secret | Leave empty (public client) |
| Code Challenge Method | `SHA-256` (S256) |
| Code Verifier | Leave empty to let Postman generate it |
| Scope | `openid` |
| State | Use a fresh random value for this authorization attempt |
| Client Authentication | `Send client credentials in body`; leave the secret empty |

If your port/hostname differs, update both OAuth URLs. The issuer must match
`KEYCLOAK_ISSUER_URI` configured for Gateway and services exactly; `localhost`
and `127.0.0.1` are different issuer strings.

Select **Get New Access Token**. In the Keycloak browser page, sign in as
`seller@nexus.local` using your locally configured `DEMO_SELLER_PASSWORD`.
Complete the return to Postman and select **Use Token**. Set requests to
**Inherit auth from parent**. Send the **access token** as
`Authorization: Bearer <access_token>`; an ID token or refresh token is not an
API bearer credential. Keep token sharing/sync disabled and do not export tokens
or passwords in collections, screenshots or comments.

## 3. Check the token and call the API

Inspect decoded claims locally; never paste a real token into a public decoder.
Decoding is inspection only: Gateway/services validate its signature and claims.

| Claim | Expected seller value |
|---|---|
| `iss` | `http://localhost:18080/realms/nexus` (or your configured issuer) |
| `aud` | Contains `nexus-services` |
| `sub` | `68000000-0000-4000-8000-000000000002` |
| `roles` | Flat array containing `SELLER` |
| `privileges` | Flat array containing `PRODUCT.CREATE` |

First call `GET http://localhost:8080/api/v1/products/mine` with that token;
expect **200**. To check creation, select an active leaf category from your local
catalog, set `categoryId` in the collection environment, and send:

```http
POST http://localhost:8080/api/v1/products
Content-Type: application/json
Authorization: Bearer <access_token>
```

```json
{
  "name": "Postman PKCE review product",
  "categoryId": "{{categoryId}}",
  "description": "Manual Keycloak token trust check",
  "auctionEnable": false
}
```

Expected: **201**, response `data.sellerId` matches the authenticated seller UUID.
This creates a real local product; record its ID for cleanup through the normal
product API if needed. The automated harness uses disposable data instead.

Obtain a separate buyer token (`buyer@nexus.local`, local `DEMO_BUYER_PASSWORD`)
and repeat the valid create request: expect **403** because BUYER lacks
`PRODUCT.CREATE`. Sign out of the seller's Keycloak browser session before
requesting the buyer token so SSO does not silently reuse the seller account.

For repeatable old-key and genuine no-audience **401** checks, run
`test-token-trust.py` as described in the [acceptance guide](README.md). It tests
Gateway and direct Catalog without modifying the team's realm or exposing tokens.
Editing a JWT payload breaks its signature and does not isolate audience validation.

## Troubleshooting

| Result | Check |
|---|---|
| `Invalid parameter: redirect_uri` | The exact Postman callback must be registered in **nexus > nexus-web**, and match the callback shown by Postman. |
| PKCE error | Use `Authorization Code (With PKCE)`, `SHA-256`, and a fresh token request. Do not reuse a code/verifier pair. |
| Browser does not return to Postman | Allow the callback to open Postman; check browser pop-up restrictions. |
| `invalid_client` | Select `nexus-web`, leave the secret empty, keep Client authentication Off in Keycloak, and send the client ID in the token request body. |
| API **401** | Check issuer hostname/port, `aud`, expiry, selected access token and JWKS reachability. A legacy user-service token is intentionally rejected. |
| API **403** | Check the selected account and exact privilege; a valid buyer cannot create products. |
| Create **400** | Check body validation and that `categoryId` is a real active leaf category; this is not evidence that token validation failed. |

Official reference: [Postman OAuth 2.0 authorization](https://learning.postman.com/docs/sending-requests/authorization/oauth-20/).
See [ADR-006](../adr/ADR-006-service-authentication-RS256.md) for the retained
privilege matrix, current trust contract, and !72/#239 merge/deployment gates.
