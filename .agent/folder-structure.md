# Folder structure

How this repository is laid out, and where new code goes.

For Maven, Config Server and Swagger rules see the backend guide. This file covers
directories only.

---

## 1. Repository root

```
nexus-project/
├─ README.md
├─ .gitignore                 OS, IDE, secrets - things that appear on both sides
├─ be-project-nexus/
│  ├─ .gitignore              Java, Maven
│  └─ .agent/                 backend-guide.md, folder-structure.md
└─ fe-project-nexus/
   ├─ .gitignore              Node, Next.js
   └─ .agents/                AGENTS.md, CLAUDE.md, UI_DESIGN_SYSTEM.md
```

Three `.gitignore` files, not one. Rules are scoped so a Java pattern cannot silently
swallow a Node file: `build/` and `dist/` mean different things on each side.

## 2. Backend

```
be-project-nexus/
├─ docs/
│  ├─ folder-structure.md
│  └─ nexus-erd.dbml
│
├─ infra/
│  └─ local-dev/
│     ├─ compose.yaml         postgres · kafka · kafka-ui · redis · mailpit · zipkin
│     │                       prometheus · grafana
│     ├─ init.sql             creates the 6 databases
│     ├─ prometheus/          scrape targets
│     ├─ grafana/             provisioned data source and dashboard
│     ├─ .env                 gitignored
│     └─ .env.example
│
├─ lib/
│  └─ pom.xml                 com.group5:nexus-parent - versions only, no code
│
├─ config-service/      :8888
├─ discovery-service/   :8761
├─ api-gateway/         :8080
│
├─ user-service/        :8081   user_db
├─ catalog-service/     :8082   catalog_db
├─ commerce-service/    :8083   commerce_db
├─ auction-service/     :8084   auction_db
├─ inventory-service/ :8085   inventory_db
└─ notification-service/:8086   notification_db
```

Every service sits at the root, next to the infrastructure ones. They are **not** grouped
under a `services/` folder, for one mechanical reason: each `pom.xml` points at its parent
with `<relativePath>../lib/pom.xml</relativePath>`. Moving services one level deeper would
break that line in every single pom and buy nothing, because `lib/pom.xml` has no
`<modules>` - there is no aggregator scanning directories.

`infra/local-dev/` is named for the environment it serves. When a deployed environment
appears, it becomes `infra/staging/` next to it rather than a second compose file nobody
can tell apart.

## 3. One service

```
user-service/
├─ pom.xml                    artifactId MUST equal the directory name
├─ mvnw · mvnw.cmd · .mvn/    each service builds and runs on its own
└─ src/
   ├─ main/
   │  ├─ java/com/group5/userservice/
   │  │  ├─ UserServiceApplication.java
   │  │  ├─ iam/
   │  │  ├─ reputation/
   │  │  └─ shared/
   │  └─ resources/
   │     ├─ application.yaml
   │     └─ db/migration/V1__init.sql
   └─ test/
      ├─ java/com/group5/userservice/
      │  ├─ iam/
      │  └─ reputation/
      └─ resources/
```

`src/test` mirrors `src/main`. A test for `iam/UserService.java` lives at
`test/.../iam/UserServiceTest.java` - no other rule needed to find it.

## 4. Inside a service: package by feature

Each direct sub-package of the root package is one feature. Inside it, files are **flat**.

```
iam/
├─ User.java                  @Entity
├─ Role.java
├─ UserRepository.java        extends JpaRepository
├─ RoleRepository.java
├─ UserService.java           @Transactional
├─ AuthService.java
├─ UserController.java
├─ AuthController.java
├─ NotificationClient.java    @FeignClient
├─ UserEventPublisher.java    writes to outbox
└─ dto/
   ├─ RegisterRequest.java
   ├─ LoginRequest.java
   └─ UserResponse.java
```

There is no `controller/`, `service/`, `repository/` directory. The layer is carried by the
class name suffix, and the call direction is unchanged:

```
Controller  ->  Service  ->  Repository  ->  Entity
```

A controller never calls a repository directly. That is the rule worth enforcing in review.

**Everything one feature needs lives in that feature's folder** - including the Feign client
it calls out with and the Kafka listener it reacts to. Only what *every* feature uses goes
to `shared/`:

```
shared/
├─ config/      SecurityConfig · OpenApiConfig
├─ security/    CurrentUser · JwtAuthConverter
└─ exception/   GlobalExceptionHandler
```

Why feature-first: changing one feature means opening one folder. And when a feature grows
large enough to become its own service, the folder moves as a unit.

## 5. Features per service

Each one maps 1:1 to a section of the SRS, so a folder name can always be traced back to a
requirement.

| Service | Features | SRS |
|---|---|---|
| `user-service` | `iam` · `reputation` | 3.1.1 · 3.1.2 |
| `notification-service` | `eventlog` · `health` · `notification` | 3.2.1 · 3.2.2 · 3.2.3 |
| `catalog-service` | `product` · `category` · `search` | 3.3.1 · 3.3.2 · 3.3.3 |
| `commerce-service` | `cart` · `checkout` · `order` | 3.4.1 · 3.4.2 · 3.4.3 |
| `auction-service` | `auction` · `bidding` · `settlement` | 3.5.1 · 3.5.2 · 3.5.3 |
| `fulfillment-service` | `inventory` · `shipping` | 3.6.1 · 3.6.2 |

## 6. Naming

```
directory          user-service              kebab-case
artifactId         user-service              same as the directory
package            com.group5.userservice    hyphens removed, no underscores
main class         UserServiceApplication
database           user_db
config file        config/user-service.yml   same as spring.application.name
```

Package names use only lowercase letters and digits with words concatenated - no
underscores. `com.group5.api_gateway` is what Spring Initializr generates from a hyphenated
artifactId; it is not the Java convention and was renamed.

Class name suffixes:

| Suffix | Meaning |
|---|---|
| `*Controller` | HTTP endpoint |
| `*Request` / `*Response` | DTO, a `record`, validated with `@Valid` |
| `*Service` | business logic, holds `@Transactional` |
| `*Repository` | `extends JpaRepository` |
| `*Client` | `@FeignClient` to another service |
| `*Consumer` | `@KafkaListener` |
| `*Publisher` | writes to `outbox` |

## 7. Where configuration lives

```
user-service/src/main/resources/application.yaml
    spring.application.name, the Config Server import and the import of
    infra/local-dev/.env. Nothing else.

config-service/src/main/resources/config/user-service.yml
    port, datasource, JPA, Swagger, business settings.

config-service/src/main/resources/config/application.yml
    what every service shares: Eureka, actuator endpoints, tracing, metrics.

infra/local-dev/.env
    credentials for docker compose and for the datasource of each service.
    Never committed.
```

Values in the local `application.yaml` **win** over values from Config Server, because
`spring.config.import` adds imported properties at a lower precedence than the file that
imports them. Keep the local file minimal for that reason, not for tidiness.

## 8. Database migrations

```
<service>/src/main/resources/db/migration/
├─ V1__init.sql
└─ V2__seed_demo.sql
```

Six services, six independent sets of migrations. No service has a migration that touches
another service's database.

`infra/local-dev/init.sql` is a different thing: it runs once when the Postgres volume is
new and only issues `CREATE DATABASE`. The tables inside come from Flyway.

A merged migration is never edited. Flyway stores a checksum, and changing the file makes
every other developer's application refuse to start. Corrections ship as a new version.

## 9. Adding something new

| Adding | Where |
|---|---|
| A business service | new directory at the backend root, `artifactId` = directory name |
| A feature inside a service | new direct sub-package next to the existing ones |
| A DTO | `<feature>/dto/` |
| A Feign client | the feature that calls it, not `shared/` |
| A Kafka listener | the feature that reacts to it |
| Something every feature uses | `shared/` |
| A schema change | a new `V<n>__*.sql`, never an edit to an applied one |
| An infrastructure container | `infra/local-dev/compose.yaml` |

## 10. Frontend

```
fe-project-nexus/
├─ app/                       Next.js App Router - routing only
│  ├─ layout.tsx
│  ├─ globals.css
│  └─ (user)/                 route group for buyer-facing pages
├─ features/                  one folder per bounded context, mirrors the backend
├─ ui/
│  └─ components/
│     ├─ atoms/               Button, Input, Badge
│     ├─ molecules/           SearchBar, ProductCard
│     ├─ organisms/           Header, ProductGrid, BidPanel
│     └─ templates/           page-level layouts
├─ hooks/
├─ lib/                       api client, STOMP client, utilities
├─ types/                     generated from OpenAPI, not written by hand
├─ constants/
└─ public/
```

Components follow Atomic Design: an atom has no dependency on another component, a molecule
composes atoms, an organism composes molecules. If a component needs to reach two levels
down to do its job, it is in the wrong level.

`features/` mirrors the backend bounded contexts by name - `catalog`, `auction`, `commerce`.
A developer changing the catalog opens `features/catalog/` on one side and
`catalog-service/src/main/java/com/group5/catalogservice/product/` on the other.

`app/` contains routing only: read the params, call the feature, handle loading and error.
No data fetching logic and no state lives there.
