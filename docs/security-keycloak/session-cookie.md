# Refresh token in an HttpOnly cookie, and CORS (#252)

## What it solves

Before this change the browser held every token. Any script that runs on the page (an XSS bug, a
compromised dependency) could read them, and the refresh token is the dangerous one because it lives for
the whole Keycloak session.

Now the browser holds only the short-lived access token, in memory. The refresh token goes to the gateway,
which keeps it in a cookie that JavaScript cannot read (`HttpOnly`).

## Who does what

| Part | Responsibility |
|---|---|
| Keycloak | Issues tokens, rotates the refresh token, owns the session. Nothing about it is stored in a Nexus database. |
| Gateway (`api-gateway/.../session`) | Three endpoints that talk to Keycloak for the browser and set or clear the cookie. |
| Frontend (`fe-project-nexus`) | Starts sign-in, hands the code to the gateway, keeps the access token in memory. |
| Services | Unchanged. They still read `Authorization: Bearer`. They never see the cookie. |

## Flows

```mermaid
sequenceDiagram
    participant B as Browser (localhost:3000)
    participant G as Gateway (localhost:8080)
    participant K as Keycloak (localhost:18080)

    Note over B,K: Sign-in. The password is typed on Keycloak's page only.
    B->>K: Authorization Code + PKCE (redirect)
    K-->>B: redirect to /auth/callback?code=...
    B->>G: POST /api/v1/auth/session {code, codeVerifier, redirectUri}
    G->>K: POST /token (authorization_code)
    K-->>G: access, id and refresh token
    G-->>B: 200 {accessToken, idToken, expiresIn} + Set-Cookie nexus_rt (HttpOnly)

    Note over B,K: Page reload or renew. No redirect, no iframe.
    B->>G: POST /api/v1/auth/refresh (cookie sent by the browser)
    G->>K: POST /token (refresh_token)
    K-->>G: new tokens; the old refresh token is now void
    G-->>B: 200 {accessToken, ...} + Set-Cookie nexus_rt (replaced)

    Note over B,K: Sign-out
    B->>G: POST /api/v1/auth/logout
    G->>K: POST /logout
    G-->>B: 204 + Set-Cookie nexus_rt; Max-Age=0
```

## The cookie

`nexus_rt=<refresh token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=<Keycloak's refresh_expires_in>`

- **HttpOnly**: the point of the change.
- **Path=/api/v1/auth**: the browser sends it to the session endpoints only, not to every API call.
- **SameSite=Lax**: the browser does not attach it to cross-site POST requests. This is the last layer, see CSRF below.
- **Secure**: on by default. Browsers accept it on `http://localhost` too. Set `SESSION_COOKIE_SECURE=false`
  only if a browser refuses it locally.
- The gateway removes the `Cookie` header from every request it forwards (`CookieStrippingFilter`).

## CSRF protection

The browser adds the cookie by itself, so another site could try to make the browser call `/auth/refresh`.
Each of the three endpoints (`SessionGuard`) requires both:

1. The header `X-Requested-With: nexus-web`. A foreign page cannot add a custom header without a CORS
   preflight, and CORS only allows our frontend origin.
2. An `Origin` that is in the allowed list, when the browser sends one.

A failed check is `403 FORBIDDEN` and the cookie is left untouched.

## CORS

The frontend (`localhost:3000`) and the gateway (`localhost:8080`) are different origins, so the browser
asks the gateway for permission first. `SecurityConfig.corsConfigurationSource` answers, and it runs
before the JWT check because a preflight carries no token.

| Setting | Where | Value |
|---|---|---|
| Allowed origins | `nexus.security.cors-allowed-origins` (Config Server, `FRONTEND_ORIGIN`) | `http://localhost:3000` locally; add STAGING and prod here |
| Credentials | code | allowed, which is why a wildcard origin is refused at startup |
| Methods | code | GET, POST, PUT, PATCH, DELETE, OPTIONS |
| Request headers | code | Authorization, Content-Type, Accept, Idempotency-Key, X-Requested-With |
| Exposed headers | code | Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining, Location |
| Max age | code | 1 hour |

If the frontend and the gateway are served from one origin (behind nginx on STAGING), no browser CORS
check happens, and the same setting stays harmless.

## Keycloak settings this depends on

- `revokeRefreshToken=true` and `refreshTokenMaxReuse=0`: every refresh returns a new refresh token and the
  old one stops working. Using an old one again is treated as theft and Keycloak ends the session. The realm
  file sets it for a new realm; `seed-users.sh` sets it on a realm that already exists.
- The cookie lives as long as Keycloak's refresh token. Today that follows `ssoSessionIdleTimeout` (1 hour).

## Several tabs

Rotation means a refresh token works once. If two tabs refreshed at the same moment they would send the
same cookie, and the second one would be refused as a replay. The frontend avoids this: `features/oidc.ts`
takes a browser lock (`navigator.locks`, name `nexus-session-refresh`) around the call, so the second tab
waits and then sends the cookie the first tab just received. Inside one tab, overlapping restores share one
call.

## Failure behavior

| Situation | Answer | Cookie |
|---|---|---|
| Missing or wrong `X-Requested-With`, or foreign `Origin` | 403 `FORBIDDEN` | untouched |
| `/refresh` without a cookie (anonymous visitor) | 204 with no body, Keycloak is not called | none to clear |
| Keycloak refuses the code or the refresh token | 401 `UNAUTHENTICATED` | cleared on `/refresh` |
| Keycloak down or answers 5xx | 503 `SERVICE_UNAVAILABLE` | kept, the token may still be good |
| Keycloak too slow (more than 5 seconds) | 504 `GATEWAY_TIMEOUT` | kept |
| `/logout` | always 204 | always cleared, even if Keycloak failed |

## Limits to know

- The three endpoints are not behind the per-route IP rate limit, which is attached to routes. A code works
  once and a rotated refresh token works once, and Keycloak has its own brute-force protection.
- This does not make the access token unreachable: it is in the page's memory for up to an hour, as before.
  What changed is that a stolen page script cannot take the long-lived credential.
- Forgot-password and email verification are separate Keycloak features (one-time action links) and are not
  part of this change.

## Verify locally

```powershell
cd be-project-nexus/api-gateway
.\mvnw.cmd test
```

`SessionControllerTest` runs the real gateway against a fake Keycloak, including token rotation and replay.
For the live stack: sign in at `http://localhost:3000/sign-in`, then in DevTools open Application, Cookies,
and check that `nexus_rt` is `HttpOnly` and that `document.cookie` does not list it.
