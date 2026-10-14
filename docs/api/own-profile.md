# Own profile API

`GET /api/v1/users/me` and `PUT /api/v1/users/me` require a valid bearer JWT.
The target user is always `CurrentUser.id()`, resolved from the validated JWT subject.
Neither endpoint accepts a target ID in its path, query, or update DTO.

## Update contract

The editable fields are `fullName`, `dateOfBirth`, `gender`, `address`, and `email`.
Omitted or null fields retain their existing values. An empty address clears its text.
Email is trimmed and lowercased before validation, duplicate checks, and persistence.
Name and address are trimmed. A supplied name must contain 1–150 characters;
email must be valid and contain at most 255 characters. Date of birth cannot be
in the future. Gender accepts `MALE`, `FEMALE`, or `OTHER`.

```json
{"fullName":"X","email":"x@nexus.local","address":"HCM"}
```

A successful update returns HTTP 200 with the existing registration `UserResponse`
shape. Password, passwordHash, ID, phone, identityNumber, status, and roles are not
editable through this DTO. Unknown body fields are ignored. Responses contain
neither a password nor a password hash.

## Errors and transaction behavior

Missing or invalid tokens return 401; invalid update data returns 400.
An email already owned by another user returns 409 and retains the entire original
profile. The same user's existing email is accepted. Soft-deleted users' emails
remain reserved because the database unique constraint covers the whole table.
Missing or soft-deleted callers return 404.

Updates lock the caller's row and execute in a transaction. `saveAndFlush` checks
database constraints before producing a response. Duplicate contact constraints,
including a concurrent duplicate email update, are converted to 409 only after
transaction rollback. Other database errors retain their original exception.

Errors use common-web/common-security `application/problem+json`; business
conflicts have `type: urn:problem:domain-error`, `title: Conflict`, `status: 409`,
`detail`, `instance`, and the current request `traceId` when tracing is active.

## Registration configuration

Config Server `config/user-service.yml` explicitly provides:

```yaml
nexus:
  iam:
    default-user-role: BUYER
    allow-self-registration: true
```

These bind to the existing `IamProperties` registration fields. Login, registration,
and JWKS remain public by their configured HTTP methods; `/users/me` remains protected.
The gateway additionally enforces its existing `PROFILE.VIEW`/`PROFILE.UPDATE` policy.

## Verification

- `UserControllerSecurityTest`: real JWT verification, security filters, current-user
  resolver, response shape, missing-token 401, invalid-input 400, conflict 409,
  ignored password and client-supplied target IDs.
- `UpdateProfileRequestTest`: normalization, optional fields, length bounds and dates.
- `UserProfileServiceTest`: allowed updates, transaction error translation and commit errors.
- `ProfileTransactionTest`: real JPA/H2 persistence, rollback and a coordinated two-user
  email race with exactly one successful update and one 409; the losing profile is unchanged.
- Existing registration, login, lockout and configuration tests remain in the suite.

Validation commands: `mvn install` in `lib`, then `mvn verify` in `user-service`
and `config-service`. The user-service run passed 144 tests and skipped 17 opt-in
PostgreSQL registration tests; common-security passed 14 and config-service passed 3.
H2 checks do not establish PostgreSQL migration or dialect compatibility. The opt-in
registration tests require their disposable PostgreSQL test database settings.
