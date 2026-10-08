# ADR-006: Downstream Service Authentication — RS256 / RSA

**Status:** Accepted ADR direction (RS256); merge still requires both backend lanes to review the implementation and this document.  
**Scope:** Access-token authentication and authorization across Project Nexus microservices.  
**Project baseline:** Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.3.  
**Primary references:** SRS 2.7 Privileges List, SRS 5.4 Security & Compliance, current API Gateway and User Service architecture.

---

## 1. Context

Project Nexus has a reactive Spring Cloud Gateway and servlet-based business services. The system needs one authentication contract that works whether a request enters through the Gateway or reaches a service directly on its own port.

The main decision is that the Gateway is not the only security boundary. The Gateway validates the bearer JWT, and every protected downstream service validates the same JWT again before executing protected business logic.

A downstream service must not trust an identity header such as `X-User-Id`, `X-Role`, or `X-Privileges` as proof of authentication.

---

## 2. Decision Summary

| Item | Decision |
|---|---|
| Access token | JWT |
| Signing algorithm | RS256 |
| Issuer (`iss`) | `user-service` |
| Audience (`aud`) | `nexus-services` |
| Public keys | `GET /.well-known/jwks.json` on `user-service` |
| Required claims | `iss`, `aud`, `sub`, `roles`, `privileges`, `iat`, `exp`, `jti` |
| Access-token lifetime | 60 minutes, configured by `nexus.iam.access-token-expiry-minutes` |
| Gateway | Validates signature, issuer, audience, timestamps; rejects invalid tokens with 401 |
| Downstream services | Validate the bearer JWT again from JWKS; do not trust identity headers |
| Direct protected-service call without token | 401 Unauthorized |
| Authorization | Roles plus privileges; SRS privilege is authoritative for endpoint access when defined |
| Password hashing | BCrypt cost factor 12 |
| RSA private key | Secret file outside the repository and Config Server, mounted read-only into `user-service` |
| Key rotation | New key pair and `kid`; JWKS serves old and new public keys during overlap |

---


## SRS Review: Roles, Privileges, and Security Requirements

This ADR was re-checked against **Project Nexus SRS v1.0** before the setup guidance below was finalized.

### SRS role model

The SRS names four business actors that participate in authorization:

| Canonical role code for JWT | SRS actor | Responsibility summary | Privileges assigned in SRS 2.7 |
|---|---|---|---:|
| `ADMIN` | Administrator (Admin) | Platform governance, user management, system configuration, moderation and operational oversight | 56 |
| `SELLER` | Seller | Lists and manages products, participates in auctions as a seller, processes seller-side commerce activities | 28 |
| `BUYER` | Buyer | Searches products, uses cart/checkout, creates orders, bids and rates transaction partners | 30 |
| `SUPPORT_STAFF` | Support Staff | Handles user support, disputes and operational support activities | 35 |

The counts above are the number of privilege rows marked for each role in the current SRS 2.7 matrix. They are useful as a review check, not as runtime constants. Runtime authorization must come from the role/privilege data stored by `user-service`.

### SRS privilege model

The current SRS 2.7 matrix contains **65 privilege rows**, grouped as follows:

| Area | Count |
|---|---:|
| `AUTH.*` | 2 |
| `PROFILE.*` | 4 |
| `USER.*` | 8 |
| `ROLE.*` | 5 |
| `PRODUCT.*` | 6 |
| `CATEGORY.*` | 5 |
| `CART.*` | 4 |
| `CHECKOUT.*` | 1 |
| `ORDER.*` | 5 |
| `AUCTION.*` | 8 |
| `SHIPPING.*` | 5 |
| `REPUTATION.*` | 4 |
| `NOTIFICATION.*` | 2 |
| `EVENT_LOG.*` | 2 |
| `AUDIT_LOG.*` | 1 |
| `SYSTEM.*` | 3 |
| **Total** | **65** |

The issue text says "66 privileges". The SRS currently contains 65. The implementation must not invent a privilege to make the count equal 66. The SRS owner or issue owner must resolve the mismatch.

### Role-management behavior is extensible

SRS 3.1.1.2 allows roles to be created and updated with assigned privileges. Therefore, the four roles above are the current business-role baseline, but the role model itself is not necessarily limited to only four database rows forever.

The JWT must carry the **actual canonical role codes** and **effective privileges** loaded from `user-service` data at login/token issuance time. Do not hard-code the full matrix in token-generation code.

### Two SRS naming/configuration mismatches to resolve

The SRS configuration list contains:

```text
DEFAULT_USER_ROLE = USER
DEFAULT_ROLE_PRIVILEGES = READ_ONLY
```

However:

- `USER` is not one of the four role columns in SRS 2.7 (`Admin`, `Seller`, `Buyer`, `Support Staff`).
- `READ_ONLY` is not one of the 65 privilege codes in SRS 2.7.

These values should not silently become JWT authorities. Before production seeding, the team must define what they mean. Two valid examples are:

1. Change the configuration to an existing canonical role/privilege policy; or
2. Keep `USER` / `READ_ONLY` as explicit application policy aliases and document how each alias expands to real stored roles and SRS privilege codes.

For self-registration, it is plausible that `USER` was intended to represent a Buyer, but the SRS does not state that mapping explicitly. Do not make that mapping without team approval.

### SRS security requirements relevant to this ADR

The SRS requires:

- OAuth 2.0 and JWT-based authentication.
- Defined token expiration and refresh-token support.
- RBAC enforcement.
- Externalized configuration and secure secret management.
- User login/authentication within the agreed `<= 500 ms` SLA.
- Access-token expiry of 60 minutes in the current configuration list.

The SRS does **not** specify RS256 or HS256. The signing algorithm is an architecture decision made by this ADR. The current ADR issue explicitly selects **RS256**, so the RS256 document is the conforming design.

---

## 3. Request Flows

### 3.1 Login and token issuance

```text
Client
  |
  | POST /auth/login
  v
API Gateway
  |
  | login is a public route
  v
user-service
  |
  | load user
  | BCrypt cost 12 password verification
  | load roles
  | load effective privileges from role_permissions
  v
JWT issuer
  |
  | sign access token with RSA private key
  | alg = RS256
  v
Client receives access token
```

The private key never leaves `user-service`.

### 3.2 Protected request through the Gateway

```text
Client
  |
  | Authorization: Bearer <JWT>
  v
API Gateway
  |
  | verify RS256 signature
  | verify iss = user-service
  | verify aud contains nexus-services
  | verify token timestamps
  |
  | invalid -> 401
  v
Downstream service
  |
  | verify the same JWT again
  | use public key from user-service JWKS
  |
  | invalid -> 401
  v
Authorization
  |
  | verify required privilege
  | missing privilege -> 403
  v
Business logic
  |
  v
Database / external dependency
```

### 3.3 Direct call to a downstream service

```text
Caller
  |
  | direct request to service port
  | no bearer token
  v
Downstream service
  |
  | protected endpoint
  v
401 Unauthorized
```

This behavior is required. A service must remain protected even when the Gateway is bypassed.

### 3.4 JWKS is not a per-request authentication call

The Gateway and downstream services validate JWTs locally using public keys obtained from JWKS. They do not send every token back to `user-service` for validation.

```text
                        user-service
                     /.well-known/jwks.json
                         /           \
                        /             \
                       v               v
                 API Gateway      downstream service
                 local verify       local verify
```

---

## 4. JWT Contract

### 4.1 Header

Example:

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "nexus-rs256-<rotation-id>"
}
```

Rules:

- `alg` must be `RS256`.
- `kid` identifies the public key in JWKS that can verify the token.
- A new key rotation must use a new `kid`.
- Validators must not accept weaker or unexpected signing algorithms.

### 4.2 Claims

| Claim | JSON type | Required | Meaning |
|---|---|---:|---|
| `iss` | string | Yes | Token issuer. Must equal `user-service`. |
| `aud` | array of strings | Yes | Intended audience. Must contain `nexus-services`. |
| `sub` | string | Yes | Stable user identifier. Do not use email as the primary subject if the email can change. |
| `roles` | array of strings | Yes | User role codes. Expected business roles are Admin, Seller, Buyer, and Support Staff. |
| `privileges` | array of strings | Yes | Effective privileges from the user's role/permission mapping. Values use the exact SRS 2.7 privilege codes such as `PRODUCT.CREATE`. |
| `iat` | number | Yes | JWT NumericDate when the token was issued. |
| `exp` | number | Yes | JWT NumericDate when the token expires. For the current SRS configuration, `exp = iat + 60 minutes`. |
| `jti` | string | Yes | Unique token identifier. |

Recommended role codes in the token:

```text
ADMIN
SELLER
BUYER
SUPPORT_STAFF
```

If the existing User Service database already uses different canonical role codes, the token must use those existing canonical codes consistently. Do not maintain two naming schemes for the same role.

### 4.3 Example decoded access token

Header:

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "nexus-rs256-2026-01"
}
```

Payload:

```json
{
  "iss": "user-service",
  "aud": ["nexus-services"],
  "sub": "64fba061-3a91-4a35-a853-221798344510",
  "roles": ["SELLER"],
  "privileges": [
    "AUTH.LOGIN",
    "AUTH.LOGOUT",
    "PROFILE.VIEW",
    "PROFILE.UPDATE",
    "PRODUCT.CREATE",
    "PRODUCT.UPDATE",
    "PRODUCT.DELETE",
    "PRODUCT.VIEW",
    "PRODUCT.LIST",
    "PRODUCT.SEARCH",
    "AUCTION.CREATE",
    "AUCTION.UPDATE",
    "AUCTION.CANCEL",
    "AUCTION.VIEW",
    "AUCTION.LIST",
    "AUCTION.VIEW_BID_HISTORY",
    "SHIPPING.QUOTE",
    "SHIPPING.VIEW_SHIPMENT",
    "REPUTATION.VIEW",
    "REPUTATION.RATE",
    "NOTIFICATION.VIEW",
    "NOTIFICATION.MANAGE_PREFERENCE"
  ],
  "iat": 1791162000,
  "exp": 1791165600,
  "jti": "2f5a9209-8d7b-42c8-afb6-cb7a05647a6a"
}
```

The example is illustrative. Runtime privileges must be loaded from the actual role/permission data, not hard-coded from this example.

---


## Cryptography Model: RSA + RS256

This section separates two terms that are easy to confuse.

### RSA and RS256 are not competing choices

`RSA` is the asymmetric cryptographic key system. It gives the deployment a mathematically related key pair:

```text
RSA key pair
|-- private key  -> secret, held by user-service
`-- public key   -> distributable, used by validators
```

`RS256` is the JWT/JWS signing algorithm used with that RSA key pair. In JWA terminology it is **RSASSA-PKCS1-v1_5 using SHA-256**.

For this project:

```text
RSA key pair + RS256

user-service
    |
    | private key + RS256
    v
SIGN JWT

Gateway / downstream services
    |
    | public key + RS256
    v
VERIFY JWT
```

There is only one JWT signing algorithm here: `RS256`. `RSA` describes the key type and asymmetric cryptographic family that RS256 uses.

### What is signed

A compact JWT has three parts:

```text
BASE64URL(header).BASE64URL(payload).BASE64URL(signature)
```

The signing input is:

```text
BASE64URL(header) + "." + BASE64URL(payload)
```

RS256 hashes that signing input with SHA-256 and creates a digital signature with the RSA private key. Validators use the matching RSA public key to verify the signature.

### Signing is not encryption

The JWT payload is not hidden by RS256. Anyone holding the token can Base64URL-decode the header and payload.

Do not put secrets in access-token claims. Claims such as `sub`, `roles`, and `privileges` are integrity-protected by the signature, but they are not confidential.

The signature proves two important properties:

1. The token was signed by an entity holding the private key expected by the system.
2. The signed header/payload has not been changed since it was signed.

### Private key vs public key

| Key | Used by | Purpose | Secret? |
|---|---|---|---|
| RSA private key | `user-service` issuer | Sign access tokens | **Yes** |
| RSA public key | Gateway and protected resource services | Verify access tokens | No |

The public key is intentionally publishable. Exposing it in JWKS does not allow a caller to derive the private key or mint a valid new token under normal cryptographic assumptions.

### Key lifecycle

The key pair is generated as a deployment/security operation, not once per login.

```text
1. Generate RSA key pair
2. Mount private/public key files into user-service
3. user-service starts and loads the pair
4. Login succeeds
5. user-service builds JWT claims
6. JwtIssuer signs the access token with the private key using RS256
7. user-service publishes public key material through JWKS
8. Gateway and downstream services cache/use JWKS public keys to verify tokens
9. Rotate keys by introducing a new kid while keeping the old public key during the overlap window
```

Local example:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

The exact key size is a deployment security baseline. The example uses 3072-bit RSA.

### Why RS256 fits this microservice design

Only `user-service` needs signing capability. Gateway and downstream services need verification capability only.

If a downstream service is compromised and only its public verification material is exposed, that material does not provide the ability to mint valid tokens. This keeps the signing authority concentrated in the issuer.

### RS256 vs HS256 at a glance

| Property | RS256 | HS256 |
|---|---|---|
| Key model | Asymmetric | Symmetric |
| Signing material | Private key | Shared secret |
| Verification material | Public key | Same shared secret |
| Can verification material mint tokens? | No | Yes |
| Public JWKS distribution | Natural fit | Secret must not be public |
| Secret scope | `user-service` only | Every signer/verifier |
| Normal overlapping rotation | Straightforward with multiple public `kid` values | Requires validators to know multiple secrets or coordinated invalidation |

The HS256 alternative is documented separately for comparison. It is not the algorithm selected by the current ADR issue.

---

## 5. Authentication Rules

A protected request is authenticated only when all required checks pass.

### 5.1 Gateway validation

The API Gateway must validate:

1. The bearer token is present on a protected route.
2. The JWT can be parsed.
3. The signature is valid using the JWKS public key selected by `kid`.
4. The signing algorithm is RS256.
5. `iss` equals `user-service`.
6. `aud` contains `nexus-services`.
7. `exp` is still valid.
8. Other standard timestamp validation performed by Spring Security succeeds.

Failure returns 401 and the request is not routed to the target service.

### 5.2 Downstream service validation

Every protected downstream service repeats the JWT validation independently.

The service must not assume that a request came through the Gateway. It must not treat a Gateway-generated identity header as authentication.

The service must use the validated JWT itself as the source of:

- user identifier: `sub`
- role identity: `roles`
- endpoint authorities: `privileges`

### 5.3 Public endpoints

Not every endpoint requires a token. Public endpoints remain callable without a bearer token where the system contract requires it.

Examples include:

- login
- registration
- password-reset entry points where applicable
- `/.well-known/jwks.json`
- public catalog/search endpoints defined as public by the API contract
- `/actuator/health` for service health checks, if approved by the deployment policy
- `/actuator/prometheus` for Prometheus metrics scraping, if monitoring is enabled

Do not implement a rule such as "every request without a token is 401" globally. The 401 requirement applies to protected endpoints.

---

## 6. Authorization Model

Authentication answers who the caller is. Authorization answers what the caller can do.

### 6.1 Roles and privileges

The access token carries both claims:

```json
{
  "roles": ["SELLER"],
  "privileges": ["PRODUCT.CREATE", "PRODUCT.UPDATE"]
}
```

Roles remain available for role identity and UI behavior. When SRS 2.7 defines a privilege for an endpoint, the service must authorize with that privilege instead of using only a broad role check.

Correct:

```java
@PreAuthorize("hasAuthority('PRODUCT.CREATE')")
```

Avoid using only this when `PRODUCT.CREATE` already exists in SRS:

```java
@PreAuthorize("hasRole('SELLER')")
```

### 6.2 Privilege population

`user-service` builds the `privileges` claim from the user's current role-to-privilege mapping, represented by `role_permissions` in the ADR task.

For users with multiple roles:

1. Load all assigned roles.
2. Load all privileges granted by those roles.
3. Create the union of the privileges.
4. Remove duplicates.
5. Put the final set into the `privileges` claim.

A token is a snapshot. If a role or privilege changes after a token is issued, the old token keeps its original claims until it expires or is otherwise invalidated by a separate mechanism.

### 6.3 SRS 2.7 privilege matrix

The following table is transcribed from the current SRS 2.7 privilege list.

| Privilege | Admin | Seller | Buyer | Support Staff |
|---|:---:|:---:|:---:|:---:|
| `AUTH.LOGIN` | X | X | X | X |
| `AUTH.LOGOUT` | X | X | X | X |
| `PROFILE.VIEW` | X | X | X | X |
| `PROFILE.UPDATE` | X | X | X | X |
| `PROFILE.CHANGE_PASSWORD` | X | X | X | X |
| `PROFILE.RESET_PASSWORD` | X | X | X | X |
| `USER.CREATE` | X |  |  |  |
| `USER.UPDATE` | X |  |  | X |
| `USER.DELETE` | X |  |  |  |
| `USER.VIEW` | X |  |  | X |
| `USER.LIST` | X |  |  | X |
| `USER.CHANGE_PASSWORD` | X |  |  |  |
| `USER.REPUTATION.VIEW` | X |  |  | X |
| `USER.REPUTATION.ADJUST` | X |  |  |  |
| `ROLE.CREATE` | X |  |  |  |
| `ROLE.UPDATE` | X |  |  |  |
| `ROLE.DELETE` | X |  |  |  |
| `ROLE.VIEW` | X |  |  | X |
| `ROLE.LIST` | X |  |  | X |
| `PRODUCT.CREATE` | X | X |  |  |
| `PRODUCT.UPDATE` | X | X |  |  |
| `PRODUCT.DELETE` | X | X |  |  |
| `PRODUCT.VIEW` | X | X | X | X |
| `PRODUCT.LIST` | X | X | X | X |
| `PRODUCT.SEARCH` | X | X | X | X |
| `CATEGORY.CREATE` | X |  |  |  |
| `CATEGORY.UPDATE` | X |  |  |  |
| `CATEGORY.DELETE` | X |  |  |  |
| `CATEGORY.VIEW` | X | X | X | X |
| `CATEGORY.LIST` | X | X | X | X |
| `CART.CREATE` |  |  | X |  |
| `CART.UPDATE` |  |  | X |  |
| `CART.REMOVE_ITEM` |  |  | X |  |
| `CART.VIEW` |  |  | X |  |
| `CHECKOUT.START` |  |  | X |  |
| `ORDER.CREATE` |  |  | X |  |
| `ORDER.CANCEL` | X |  | X | X |
| `ORDER.VIEW` | X | X | X | X |
| `ORDER.LIST` | X | X | X | X |
| `ORDER.REFUND` | X |  |  | X |
| `AUCTION.CREATE` | X | X |  |  |
| `AUCTION.UPDATE` | X | X |  |  |
| `AUCTION.CANCEL` | X | X |  |  |
| `AUCTION.ADMIN_CANCEL` | X |  |  |  |
| `AUCTION.VIEW` | X | X | X | X |
| `AUCTION.LIST` | X | X | X | X |
| `AUCTION.BID` |  |  | X |  |
| `AUCTION.VIEW_BID_HISTORY` | X | X | X | X |
| `SHIPPING.QUOTE` | X | X | X | X |
| `SHIPPING.CREATE_SHIPMENT` | X |  |  | X |
| `SHIPPING.VIEW_SHIPMENT` | X | X | X | X |
| `SHIPPING.UPDATE_STATUS` | X |  |  | X |
| `SHIPPING.MANUAL_OVERRIDE` | X |  |  | X |
| `REPUTATION.VIEW` | X | X | X | X |
| `REPUTATION.RATE` |  | X | X |  |
| `REPUTATION.PENALTY.VIEW` | X |  |  | X |
| `REPUTATION.PENALTY.APPLY` | X |  |  |  |
| `NOTIFICATION.VIEW` | X | X | X | X |
| `NOTIFICATION.MANAGE_PREFERENCE` |  | X | X |  |
| `EVENT_LOG.VIEW` | X |  |  | X |
| `EVENT_LOG.VIEW_DETAIL` | X |  |  | X |
| `AUDIT_LOG.VIEW` | X |  |  |  |
| `SYSTEM.CONFIG.VIEW` | X |  |  |  |
| `SYSTEM.CONFIG.UPDATE` | X |  |  |  |
| `SYSTEM.HEALTH.VIEW` | X |  |  | X |

### 6.4 SRS count discrepancy

The ADR issue text says SRS 2.7 defines **66 privileges**. The current SRS v0.7 section 2.7 contains **65 privilege rows**.

Do not invent a 66th privilege to make the count match the issue. Before the merge request is approved, the backend reviewers should confirm whether:

- the SRS is missing one privilege, or
- the issue text should be corrected from 66 to 65.

The privilege names and assignments in the table above follow the current SRS document.

---

## 7. Service Setup

The code below is a reference implementation for the current Spring Boot/Spring Security stack. Package names should match the actual service package structure.

### 7.1 Shared Maven dependency for JWT resource servers

Add this dependency to the Gateway and every service that validates bearer JWTs:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

Do not put this into the parent POM unless every child application is intentionally a resource server. Keep service-specific dependencies in each service POM according to the project dependency rules.

`user-service` also needs this starter if it has protected user/role/profile endpoints, in addition to issuing tokens.

---

## 8. User Service Setup

`user-service` has four security responsibilities:

1. Verify username/password during login.
2. Load roles and effective privileges.
3. Sign access tokens with the RSA private key.
4. Publish public RSA keys through JWKS.

### 8.1 Configuration

Example service configuration:

```yaml
nexus:
  iam:
    access-token-expiry-minutes: 60

security:
  jwt:
    issuer: user-service
    audience: nexus-services
    key-id: ${JWT_KEY_ID}
    private-key-path: ${JWT_PRIVATE_KEY_PATH}
    public-key-path: ${JWT_PUBLIC_KEY_PATH}
```

Environment values contain file paths, not PEM contents:

```text
JWT_KEY_ID=nexus-rs256-<rotation-id>
JWT_PRIVATE_KEY_PATH=/run/secrets/nexus-jwt-private.pem
JWT_PUBLIC_KEY_PATH=/run/secrets/nexus-jwt-public.pem
```

Do not set an environment variable such as this:

```text
JWT_PRIVATE_KEY="-----BEGIN PRIVATE KEY----- ..."
```

### 8.2 Properties object

```java
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String keyId,
        Path privateKeyPath,
        Path publicKeyPath
) {
}

@ConfigurationProperties(prefix = "nexus.iam")
public record IamProperties(
        long accessTokenExpiryMinutes
) {
}
```

`nexus.iam.access-token-expiry-minutes` is the canonical access-token lifetime setting for this ADR so the document matches the existing issue/config naming.

### 8.3 PEM key loader

The following loader expects a PKCS#8 private key and an X.509 SubjectPublicKeyInfo public key.

```java
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class PemKeyLoader {

    private PemKeyLoader() {
    }

    public static RSAPrivateKey readPrivateKey(Path path)
            throws IOException, GeneralSecurityException {

        String pem = Files.readString(path, StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] der = Base64.getDecoder().decode(pem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);

        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(keySpec);
    }

    public static RSAPublicKey readPublicKey(Path path)
            throws IOException, GeneralSecurityException {

        String pem = Files.readString(path, StandardCharsets.UTF_8)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] der = Base64.getDecoder().decode(pem);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(der);

        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(keySpec);
    }
}
```

### 8.4 RSA/JWT beans

```java
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, IamProperties.class})
public class JwtKeyConfig {

    @Bean
    RSAPrivateKey jwtPrivateKey(JwtProperties properties) throws Exception {
        return PemKeyLoader.readPrivateKey(properties.privateKeyPath());
    }

    @Bean
    RSAPublicKey jwtPublicKey(JwtProperties properties) throws Exception {
        return PemKeyLoader.readPublicKey(properties.publicKeyPath());
    }

    @Bean
    RSAKey rsaJwk(
            RSAPublicKey publicKey,
            RSAPrivateKey privateKey,
            JwtProperties properties
    ) {
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(properties.keyId())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey rsaJwk) {
        JWKSource<SecurityContext> jwkSource =
                new ImmutableJWKSet<>(new JWKSet(rsaJwk));

        return new NimbusJwtEncoder(jwkSource);
    }
}
```

### 8.5 JWT issuer

```java
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final IamProperties iamProperties;
    private final Clock clock;

    public JwtIssuer(
            JwtEncoder jwtEncoder,
            JwtProperties properties,
            IamProperties iamProperties
    ) {
        this(jwtEncoder, properties, iamProperties, Clock.systemUTC());
    }

    JwtIssuer(
            JwtEncoder jwtEncoder,
            JwtProperties properties,
            IamProperties iamProperties,
            Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.iamProperties = iamProperties;
        this.clock = clock;
    }

    public String issueAccessToken(
            String userId,
            Collection<String> roles,
            Collection<String> privileges
    ) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(
                Duration.ofMinutes(iamProperties.accessTokenExpiryMinutes())
        );

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId())
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(userId)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", List.copyOf(roles))
                .claim("privileges", List.copyOf(privileges))
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}
```

The service that calls `issueAccessToken` must load privileges from the database. The issuer must not maintain a separate hard-coded privilege list.

### 8.6 JWKS endpoint

The endpoint publishes public material only.

```java
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

    private final RSAKey rsaJwk;

    public JwksController(RSAKey rsaJwk) {
        this.rsaJwk = rsaJwk;
    }

    @GetMapping(
            value = "/.well-known/jwks.json",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> jwks() {
        RSAKey publicJwk = rsaJwk.toPublicJWK();
        return new JWKSet(publicJwk).toJSONObject();
    }
}
```

Expected shape:

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "nexus-rs256-<rotation-id>",
      "n": "...",
      "e": "AQAB"
    }
  ]
}
```

The JWKS response must never contain private RSA parameters.

### 8.7 BCrypt cost 12

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

`AuthService` must use this `PasswordEncoder` for password matching and password creation.

The seed migration must also use cost 12. If the seed inserts a pre-generated BCrypt hash, verify that the encoded hash records cost 12. Do not commit a plaintext seed password for staging or production.

---

## 9. Shared JWT Validators

The same issuer/audience validation rules must be used by the Gateway and servlet services.

```java
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String requiredAudience;
    private final OAuth2Error error = new OAuth2Error(
            "invalid_token",
            "Required audience is missing",
            null
    );

    public AudienceValidator(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience().contains(requiredAudience)) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(error);
    }
}
```

This class can live in a shared internal security library if the team already has an approved shared-library strategy. Otherwise, keep the implementation local to avoid coupling services only for a small class.

---

## 10. API Gateway Setup

The API Gateway uses WebFlux. It validates access tokens before routing protected requests.

### 10.1 Gateway configuration

```yaml
security:
  jwt:
    issuer: user-service
    audience: nexus-services
    jwk-set-uri: ${USER_SERVICE_JWKS_URI}
```

Example environment value:

```text
USER_SERVICE_JWKS_URI=http://<user-service-host>:<port>/.well-known/jwks.json
```

Use the deployment-specific internal hostname or stable service address. Do not hard-code a developer's localhost URL into shared staging/production configuration.

### 10.2 Reactive decoder

```java
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@Configuration
public class GatewayJwtConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${security.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.audience}") String audience
    ) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(issuer);

        OAuth2TokenValidator<Jwt> audienceValidator =
                new AudienceValidator(audience);

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        issuerValidator,
                        audienceValidator
                )
        );

        return decoder;
    }
}
```

### 10.3 Gateway security chain

Public route patterns must match the actual API contract and Gateway rewrite rules.

```java
import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // Replace these examples with the actual routed paths.
                        .pathMatchers(
                                "/auth/login",
                                "/auth/register"
                        ).permitAll()
                        .pathMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()))
                .build();
    }
}
```

The Gateway should classify public/protected routes, but endpoint-level business authorization remains the downstream service's responsibility.

### 10.4 Header rule

For protected routes, the original `Authorization: Bearer <JWT>` must still reach the downstream service.

Do not replace the bearer token with only these headers:

```text
X-User-Id
X-Role
X-Privileges
```

If convenience identity headers are ever added for logging, they are not authentication evidence. The Gateway should also remove equivalent client-supplied identity headers before adding its own values to avoid spoofing and ambiguity.

---

## 11. Downstream Servlet Service Setup

This applies to protected servlet-based services such as catalog/product, order/commerce, auction, fulfillment, shipment, inventory, and other business APIs.

Each service is an OAuth2 resource server.

### 11.1 Service configuration

```yaml
security:
  jwt:
    issuer: user-service
    audience: nexus-services
    jwk-set-uri: ${USER_SERVICE_JWKS_URI}

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

`/actuator/health` is used for service health checks. `/actuator/prometheus` exposes metrics for Prometheus scraping. Security must permit both paths when these endpoints are intentionally exposed.

### 11.2 Servlet JWT decoder

```java
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${security.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.audience}") String audience
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(issuer);

        OAuth2TokenValidator<Jwt> audienceValidator =
                new AudienceValidator(audience);

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        issuerValidator,
                        audienceValidator
                )
        );

        return decoder;
    }
}
```

### 11.3 Map roles and privileges to Spring authorities

Privileges keep their exact SRS names. Roles use the conventional `ROLE_` prefix so `hasRole(...)` still works where role checks are intentionally needed.

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.DelegatingJwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Configuration
public class JwtAuthoritiesConfig {

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roleConverter =
                new JwtGrantedAuthoritiesConverter();
        roleConverter.setAuthoritiesClaimName("roles");
        roleConverter.setAuthorityPrefix("ROLE_");

        JwtGrantedAuthoritiesConverter privilegeConverter =
                new JwtGrantedAuthoritiesConverter();
        privilegeConverter.setAuthoritiesClaimName("privileges");
        privilegeConverter.setAuthorityPrefix("");

        DelegatingJwtGrantedAuthoritiesConverter authoritiesConverter =
                new DelegatingJwtGrantedAuthoritiesConverter(
                        roleConverter,
                        privilegeConverter
                );

        JwtAuthenticationConverter authenticationConverter =
                new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(
                authoritiesConverter
        );

        return authenticationConverter;
    }
}
```

A token containing:

```json
{
  "roles": ["SELLER"],
  "privileges": ["PRODUCT.CREATE"]
}
```

produces authorities equivalent to:

```text
ROLE_SELLER
PRODUCT.CREATE
```

### 11.4 Servlet security chain

```java
import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Add only service-specific public endpoints here.
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/prometheus"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                );

        return http.build();
    }
}
```

### 11.5 Endpoint authorization example

```java
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class ProductController {

    @PreAuthorize("hasAuthority('PRODUCT.CREATE')")
    @PostMapping
    public ProductResponse createProduct(
            @RequestBody CreateProductRequest request
    ) {
        // business call
        return null;
    }
}
```

Use the exact SRS privilege string. Do not rename `PRODUCT.CREATE` to `PRODUCT_CREATE` in only one service.

### 11.6 Read the authenticated user from the validated JWT

```java
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserController {

    @GetMapping("/internal/example")
    public String example(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return userId;
    }
}
```

Do not read `X-User-Id` as the authoritative user identity for protected business logic.

---

## 12. User Service as a Protected Resource Server

`user-service` is both the issuer and a resource server for protected profile/user/role endpoints.

It can validate its own tokens using its local public key instead of making an HTTP call to its own JWKS endpoint.

```java
import java.security.interfaces.RSAPublicKey;

import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Bean
JwtDecoder userServiceJwtDecoder(
        RSAPublicKey publicKey,
        JwtProperties properties
) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

    decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(
                            properties.issuer()
                    ),
                    new AudienceValidator(properties.audience())
            )
    );

    return decoder;
}
```

Public endpoints such as login, registration, password-reset entry points, and JWKS must be explicitly permitted. Other protected User Service endpoints must require a valid token and appropriate privilege.

---

## 13. Config Service and Discovery Service

### 13.1 Config Service

Config Server may contain non-secret security configuration such as:

```yaml
security:
  jwt:
    issuer: user-service
    audience: nexus-services
```

It must not contain:

- RSA private PEM content
- a committed private key file
- production passwords
- production API keys
- secret-manager credentials

The private key path may be provided by deployment environment configuration, but the private PEM itself remains outside Config Server.

### 13.2 Discovery Service

Eureka/Discovery does not authenticate users and does not issue or validate user JWTs as part of this ADR.

It only provides service registration/discovery. Its administrative exposure is a deployment/security concern and must not be confused with end-user JWT authentication.

---

## 14. Private Key Storage

### 14.1 Repository rule

The RSA private key must not be committed to:

```text
Git repository
Config Server repository/folder
shared .env file
application.yml
application-*.yml
Docker image layer
```

The application receives only a path to the secret file.

### 14.2 Local development

A developer generates and keeps the local key pair outside the project repository.

Example directory layout:

```text
workspace/
|-- be-project-nexus/
|   `-- ...
`-- nexus-local-secrets/
    |-- jwt-private.pem
    `-- jwt-public.pem
```

Example key generation for local development:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

The exact RSA key size should follow the team's security baseline. The example uses 3072 bits and does not change the ADR's RS256 algorithm decision.

### 14.3 Docker example

The mounted source path should point outside the repository.

```yaml
services:
  user-service:
    volumes:
      - ../nexus-local-secrets/jwt-private.pem:/run/secrets/nexus-jwt-private.pem:ro
      - ../nexus-local-secrets/jwt-public.pem:/run/secrets/nexus-jwt-public.pem:ro
    environment:
      JWT_KEY_ID: nexus-rs256-local-01
      JWT_PRIVATE_KEY_PATH: /run/secrets/nexus-jwt-private.pem
      JWT_PUBLIC_KEY_PATH: /run/secrets/nexus-jwt-public.pem
```

### 14.4 Staging and production

The deployment owner must provide the private key through the selected secret-management mechanism and mount it read-only into the `user-service` container.

The repository and Config Server must remain unaware of the private PEM content.

---

## 15. Key Rotation Procedure

Normal rotation must not invalidate still-valid access tokens signed by the old key.

### 15.1 Normal rotation

1. Generate a new RSA key pair.
2. Assign a new unique `kid`.
3. Add the new public key to JWKS while keeping the old public key published.
4. Confirm the JWKS endpoint returns both public keys.
5. Switch `JwtIssuer` to sign new tokens with the new private key and new `kid`.
6. Keep the old public key in JWKS until every access token signed with the old key has expired.
7. Include the permitted clock-skew/operational safety window before removing the old public key.
8. Remove the old key after the overlap window.
9. Record the rotation in deployment/audit records.

During the overlap:

```text
JWKS
|-- old-public-key, kid = old
`-- new-public-key, kid = new

JwtIssuer
`-- signs new tokens only with new-private-key
```

### 15.2 Emergency compromise

If an old private key is believed to be compromised, the normal overlap rule may be unsafe. Security owners may remove the compromised public key immediately, which intentionally causes tokens signed by that key to fail validation with 401.

Emergency revocation policy is operational policy and should be handled separately from normal rotation.

---

## 16. HTTP Failure Behavior

| Situation | Expected result |
|---|---|
| Protected endpoint with no bearer token | 401 |
| Malformed JWT | 401 |
| Invalid RSA signature | 401 |
| Unknown/removed `kid` | 401 |
| Wrong `iss` | 401 |
| Missing/wrong `aud` | 401 |
| Expired token | 401 |
| Valid token but missing required privilege | 403 |
| Public endpoint with no token | Allowed, subject to endpoint contract |

The Gateway may reject the request before it reaches the service. If a caller bypasses the Gateway and calls a protected service directly, the service must produce the same authentication result for an invalid or missing token.

---

## 17. BCrypt Rule and Login SLA

The password encoder cost factor is fixed at 12 for this decision.

Both of these must use cost 12:

- `AuthService`
- seed migration / seed password hashes

The SRS authentication SLA requires login to remain within 500 ms in the agreed test environment.

Do not claim this acceptance criterion from configuration alone. Before merge, record an actual measurement with:

- test environment CPU/RAM
- database location
- number of warm-up requests
- number of measured requests
- p50/p95 or the team's agreed metric
- observed result with BCrypt cost 12

If cost 12 breaches the 500 ms SLA in the agreed environment, the team must resolve the conflict explicitly rather than silently changing either the cost or the SLA.

---

## 18. Required Tests

### 18.1 Token contract tests

Verify an issued token contains:

```text
iss = user-service
aud contains nexus-services
sub is present
roles is present
privileges is present
iat is present
exp is present
jti is present
alg = RS256
kid is present
```

Verify `exp - iat` is 60 minutes for the current SRS configuration.

### 18.2 Gateway authentication tests

At minimum:

- valid token -> request is routed
- no token on protected route -> 401
- expired token -> 401
- wrong issuer -> 401
- wrong audience -> 401
- invalid signature -> 401

### 18.3 Downstream service authentication tests

Call the service directly, not through the Gateway:

```text
protected endpoint + no token -> 401
protected endpoint + invalid token -> 401
protected endpoint + valid token -> request enters authorization
```

This test is mandatory because it proves that the service does not depend on the Gateway for authentication.

### 18.4 Authorization tests

For an endpoint guarded by `PRODUCT.CREATE`:

```text
valid token + PRODUCT.CREATE -> allowed
valid token - PRODUCT.CREATE -> 403
```

Also verify the code uses the exact SRS privilege string.

### 18.5 Header trust test

Send a direct protected request with fake identity headers and no bearer token:

```http
X-User-Id: admin-user
X-Role: ADMIN
X-Privileges: PRODUCT.CREATE
```

Expected result:

```text
401 Unauthorized
```

The headers must not authenticate the request.

### 18.6 JWKS rotation test

During a test rotation:

1. JWKS publishes old and new public keys.
2. A token signed by the old private key remains valid until expiration.
3. A token signed by the new private key is valid.
4. After the old public key is removed, an old-key token is rejected.

### 18.7 BCrypt tests

Verify:

- application encoder strength is 12
- seed hashes use cost 12
- valid password matches
- invalid password does not match
- login meets the agreed 500 ms SLA test

---

## 19. Service Rollout Checklist

### `user-service`

- [ ] `spring-boot-starter-oauth2-resource-server` added if protected endpoints exist.
- [ ] BCrypt encoder uses cost 12.
- [ ] Seed migration uses cost 12.
- [ ] Private key is loaded from a mounted secret file.
- [ ] Private key is not stored in Git or Config Server.
- [ ] JWT uses RS256.
- [ ] `kid` is present.
- [ ] Required claims are populated.
- [ ] Access-token lifetime is read from `nexus.iam.access-token-expiry-minutes`.
- [ ] `privileges` comes from role/permission data.
- [ ] `/.well-known/jwks.json` exposes public key material only.
- [ ] Login performance evidence is recorded.

### `api-gateway`

- [ ] OAuth2 Resource Server dependency added.
- [ ] Reactive JWT decoder uses JWKS.
- [ ] RS256 is enforced.
- [ ] `iss` is validated.
- [ ] `aud` is validated.
- [ ] token expiration is validated.
- [ ] protected route without token returns 401.
- [ ] original bearer token is forwarded to downstream services.
- [ ] identity headers are not treated as the downstream trust contract.

### Every protected downstream service

- [ ] OAuth2 Resource Server dependency added.
- [ ] service validates JWT independently from JWKS.
- [ ] RS256 is enforced.
- [ ] `iss` is validated.
- [ ] `aud` is validated.
- [ ] token expiration is validated.
- [ ] roles and privileges are mapped to Spring authorities.
- [ ] endpoint permissions use exact SRS privilege codes.
- [ ] direct protected call without token returns 401.
- [ ] valid token without required privilege returns 403.
- [ ] fake identity headers without token do not authenticate the request.
- [ ] `/actuator/health` and `/actuator/prometheus` are exposed/permitted according to the monitoring policy.

### Config and deployment

- [ ] Config Server contains no private PEM content.
- [ ] production secret is owned by the deployment/secret-management process.
- [ ] private secret mount is read-only.
- [ ] JWKS URI is environment/deployment appropriate.
- [ ] rotation procedure has been tested.

---

## 20. Acceptance Criteria Traceability

| Issue acceptance criterion | Covered by this ADR |
|---|---|
| Every claim is named with type, meaning, and one example token | Sections 4.1-4.3 |
| Direct protected service call with no token is defined | Sections 3.3, 5.2, 16, 18.3 |
| Key location is outside repository/Config Server and rotation is documented | Sections 14-15 |
| Privileges are populated and enforced instead of being unused claims | Sections 6, 8.5, 11.3-11.5 |
| BCrypt cost 12 is used by AuthService and seed migration | Sections 8.7, 17, 18.7 |
| Login with cost 12 remains within 500 ms SLA | Section 17 defines required evidence; actual measurement must be attached/recorded before merge |
| Both backend lanes approve the merge request | Process requirement; cannot be satisfied by the document alone |

---

## 21. Out of Scope

This ADR does not define:

- refresh-token storage or rotation
- token blacklist/revocation architecture
- OAuth consent flows
- service-to-service machine credentials
- mutual TLS implementation
- API Gateway routing design
- detailed CORS policy
- secret-manager vendor selection
- emergency incident-response procedure beyond the key-removal behavior described above

SRS 5.4 requires refresh-token support, but refresh tokens are not accepted by Gateway/downstream resource servers as access tokens. Their storage and lifecycle should be documented separately.

---

## 22. Consequences

### Positive

- A compromised or bypassed Gateway does not automatically make a downstream service trust an unauthenticated request.
- Services share one JWT contract.
- RSA private material stays only with the token issuer.
- Public-key rotation can happen without invalidating all active tokens during normal rotation.
- Endpoint authorization follows the SRS privilege matrix instead of broad role checks.

### Cost

- JWT verification occurs at both Gateway and downstream service.
- Every protected service needs Resource Server configuration.
- JWKS availability/caching and key rotation become operational concerns.
- Role/privilege changes are not visible inside already-issued access tokens until those tokens expire or a separate revocation mechanism is introduced.

---

## 23. Final Rules

The following rules are non-negotiable for implementations based on this ADR:

1. `user-service` is the JWT issuer.
2. Access tokens use RS256.
3. The RSA private key is never committed and never stored in Config Server.
4. `user-service` publishes public keys at `/.well-known/jwks.json`.
5. Gateway validates JWTs on protected routes.
6. Downstream services validate JWTs again.
7. Downstream services do not trust identity headers as authentication.
8. Direct calls to protected endpoints without a token return 401.
9. Authorization uses exact SRS privilege codes when a privilege is defined.
10. BCrypt cost factor is 12 for authentication and seed hashes.
11. Normal key rotation keeps old and new public keys in JWKS during the overlap window.
12. The SRS privilege-count mismatch (issue says 66, current SRS contains 65) must be resolved before final approval.
