# NEXUS Microservices Project Guide

> **Purpose:** This document is the required onboarding guide for developers working on this project.  
> Read this document before creating, modifying, or integrating a microservice.

---

## 1. Project Architecture

This project follows a Spring Cloud microservices architecture.

Main infrastructure services:

- **Config Service** — centralized configuration server.
- **Discovery Service** — Eureka Server / Service Registry.
- **API Gateway** — single entry point and request routing.
- **Business Services** — Student, Order, Warehouse, Shipment, Inventory, and other domain services.

High-level flow:

```text
                         Client
                           |
                           v
                    +--------------+
                    | API Gateway  |
                    |    :8080     |
                    +------+-------+
                           |
                  Service discovery
                           |
              +------------+-------------+
              |                          |
              v                          v
       Business Service           Business Service
       (Student, Order, ...)       (Warehouse, ...)
              |
              | configuration
              v
       +----------------+
       | Config Service |
       |     :8888      |
       +----------------+
              |
              v
    resources/config/*.yml

Discovery Service runs independently as the Eureka registry:

       +-------------------+
       | Discovery Service |
       |       :8761       |
       +-------------------+
```

---

## 2. Project Directory Convention

Recommended root structure:

```text
be-project-nexus/
|
|-- lib/
|   `-- pom.xml
|
|-- config-service/
|   |-- pom.xml
|   `-- src/main/resources/
|       |-- application.yml
|       `-- config/
|           |-- application.yml
|           |-- service-discovery.yml
|           |-- api-gateway.yml
|           |-- student-service.yml
|           `-- <other-service>.yml
|
|-- service-discovery/
|   |-- pom.xml
|   `-- src/main/resources/application.yml
|
|-- api-gateway/
|   |-- pom.xml
|   `-- src/main/resources/application.yml
|
|-- student-service/
|   |-- pom.xml
|   `-- src/main/resources/application.yml
|
`-- <other-business-service>/
    |-- pom.xml
    `-- src/main/resources/application.yml
```

---

# 3. Maven Dependency Strategy

## 3.1 Parent POM Rule

The shared parent POM is located at:

```text
lib/pom.xml
```

The parent POM must contain only configuration that is truly shared by the project, for example:

- Spring Boot parent version.
- Java version.
- Spring Cloud version.
- Spring Cloud BOM / dependency management.
- Build configuration that every Spring Boot service uses.
- Dependencies that **every child service really needs**.

Do **not** move a dependency to the parent only because several services use it.

For example:

```text
spring-cloud-config-server
```

belongs only to `config-service`.

```text
spring-cloud-starter-netflix-eureka-server
```

belongs only to `service-discovery`.

```text
spring-cloud-starter-gateway-server-webflux
```

belongs only to `api-gateway`.

A business-specific dependency should stay inside that service's own `pom.xml`.

---

## 3.2 Parent POM Example

`lib/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <groupId>com.group5</groupId>
    <artifactId>nexus-parent</artifactId>
    <version>1.0.0</version>

    <packaging>pom</packaging>

    <name/>
    <description/>
    <url/>

    <licenses>
        <license/>
    </licenses>

    <developers>
        <developer/>
    </developers>

    <scm>
        <connection/>
        <developerConnection/>
        <tag/>
        <url/>
    </scm>

    <properties>
        <java.version>21</java.version>
        <spring-cloud.version>2025.1.3</spring-cloud.version>
    </properties>

    <dependencyManagement>
        <dependencies>

            <!--
                Spring Cloud BOM:
                Centralizes compatible Spring Cloud dependency versions.
                It does NOT automatically add Eureka, Config Client,
                Gateway, OpenFeign, etc. to child services.
            -->
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>

            <!-- Common Spring Boot build plugin -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

        </plugins>
    </build>

</project>
```

### Important: `dependencyManagement` vs `dependencies`

`dependencyManagement` manages versions and defaults.

It does **not** automatically install the dependency into every service.

Example:

```xml
<dependencyManagement>
    ...
</dependencyManagement>
```

means:

> "If a child service uses this dependency, use the managed version."

But:

```xml
<dependencies>
    ...
</dependencies>
```

inside the parent means:

> "Every child service inherits this dependency."

Therefore, use parent `<dependencies>` carefully.

---

# 4. Service Dependency Responsibilities

## 4.1 Config Service

Purpose:

- Runs the Spring Cloud Config Server.
- Stores and serves centralized YAML configuration.
- In this project, configuration is stored locally under `resources/config/`.
- GitHub/GitLab is not required for the Config Server backend.

Main dependency:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-config-server</artifactId>
</dependency>
```

Meaning:

> This application is the **configuration provider**.

The main application class must enable Config Server:

```java
@SpringBootApplication
@EnableConfigServer
public class ConfigServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServiceApplication.class, args);
    }
}
```

---

## 4.2 Config Client

Services that retrieve configuration from Config Service use:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

Meaning:

> This application is a **configuration consumer/client**.

Typical Config Clients:

- API Gateway
- Discovery Service, if its configuration is centralized
- Student Service
- Order Service
- Warehouse Service
- Shipment Service
- Inventory Service
- Other business services

Do not confuse the two dependencies:

```text
spring-cloud-config-server
    = PROVIDES configuration

spring-cloud-starter-config
    = RETRIEVES configuration
```

---

## 4.3 Discovery Service

Purpose:

- Runs Eureka Server.
- Acts as the Service Registry.
- Allows services to register themselves and discover other services.

Main dependency:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>
```

If Discovery configuration is stored in Config Server, also add:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

---

## 4.4 API Gateway

Purpose:

- Single entry point for external clients.
- Routes requests to business services.
- Uses Eureka to locate services.
- Uses WebFlux.
- Hosts/aggregates Swagger UI configuration.

Typical dependencies:

```xml
<!-- API Gateway / WebFlux -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
</dependency>

<!-- Eureka Client -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>

<!-- Config Client -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>

<!-- Swagger / OpenAPI for WebFlux -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

Do not use the Spring MVC Swagger starter in API Gateway because Gateway is WebFlux-based.

---

# 5. Standard Business Service POM

Business services should follow the same POM structure.

The example below is the standard template based on `student-service`.

Only keep dependencies that the service actually needs.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.group5</groupId>
        <artifactId>nexus-parent</artifactId>
        <version>1.0.0</version>
        <relativePath>../lib/pom.xml</relativePath>
    </parent>

    <groupId>com.group5</groupId>
    <artifactId>student-service</artifactId>
    <version>0.0.1-SNAPSHOT</version>

    <name/>
    <description/>
    <url/>

    <licenses>
        <license/>
    </licenses>

    <developers>
        <developer/>
    </developers>

    <scm>
        <connection/>
        <developerConnection/>
        <tag/>
        <url/>
    </scm>

    <dependencies>

        <!--
            Spring Web MVC:
            Builds REST APIs using:
            @RestController, @GetMapping, @PostMapping, etc.
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>

        <!--
            Eureka Client:
            Registers the service with Eureka / Discovery Service.
        -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!--
            Config Client:
            Retrieves application.yml + <service-name>.yml
            from Config Server.
        -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>

        <!--
            Spring Data JPA:
            Provides Entity, Repository, Hibernate and ORM support.
            Keep this only when the service has a relational database.
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!--
            PostgreSQL Driver:
            Allows JDBC/Hibernate to connect to PostgreSQL.
            Keep this only when the service uses PostgreSQL.
        -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!--
            Lombok:
            Reduces boilerplate for getters, setters,
            constructors, builders, etc.
        -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!--
            Swagger/OpenAPI for Spring MVC:
            Generates /v3/api-docs and Swagger UI.
        -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>3.1.1</version>
        </dependency>

        <!--
            DevTools:
            Automatic restart during local development.
            Development only.
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>

        <!--
            Spring MVC Test:
            Supports Controller/Web MVC tests.
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>

    </dependencies>

</project>
```

### Business Service Dependency Rule

Do not blindly copy every dependency.

Examples:

- Service without a database -> remove JPA and PostgreSQL.
- Service without Lombok usage -> remove Lombok.
- Service without Swagger requirement -> remove Springdoc.
- Service using another database -> replace PostgreSQL with the correct driver.

---

# 6. Centralized Configuration Strategy

The project uses Spring Cloud Config with a **native/local backend**.

Config Service reads YAML files from:

```text
config-service/src/main/resources/config/
```

Config Service itself is configured by:

```text
config-service/src/main/resources/application.yml
```

These two `application.yml` files have different purposes.

---

## 6.1 Config Service's Own `application.yml`

Path:

```text
config-service/src/main/resources/application.yml
```

Example:

```yaml
server:
  port: 8888

spring:
  application:
    name: config-service

  profiles:
    active: native

  cloud:
    config:
      server:
        native:
          search-locations: classpath:/config/

management:
  endpoints:
    web:
      exposure:
        include: "*"
```

Purpose:

- Runs Config Server on port `8888`.
- Activates the `native` backend.
- Tells Config Server to read centralized configuration from `/config/`.

This file is **not** the common configuration for business services.

---

# 7. Common Configuration

Path:

```text
config-service/src/main/resources/config/application.yml
```

`application.yml` inside the Config repository is a special Spring Cloud Config convention.

It is automatically treated as common configuration.

Example:

```yaml
# =====================================================
# COMMON CONFIGURATION
# Applied to Config Client services
# =====================================================

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

Spring Cloud Config automatically combines:

```text
application.yml
+
<service-name>.yml
```

for a requesting service.

Example:

```text
student-service
      |
      v
application.yml
+
student-service.yml
```

No custom code is required to mark `application.yml` as common.

### Important rule for common configuration

Only put properties in `config/application.yml` when they are truly shared by the Config Client services.

Good example:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

Do **not** put Swagger/Springdoc configuration in the common `application.yml`.

Reason:

- API Gateway uses **Spring WebFlux** + `springdoc-openapi-starter-webflux-ui`.
- Normal business services use **Spring MVC** + `springdoc-openapi-starter-webmvc-ui`.
- Discovery Service does not normally need Swagger.
- Config Service does not normally need Swagger.

Therefore, Swagger configuration must stay in the YAML file of the service that actually owns it.

---

# 8. Service-Specific Configuration

Configuration files must match `spring.application.name`.

Example:

```text
spring.application.name = student-service
```

must use:

```text
config/student-service.yml
```

Gateway:

```text
spring.application.name = api-gateway
```

must use:

```text
config/api-gateway.yml
```

Discovery:

```text
spring.application.name = service-discovery
```

must use:

```text
config/service-discovery.yml
```

General rule:

```text
spring.application.name
        |
        v
<spring.application.name>.yml
```

---

# 9. Local `application.yml` Required in Every Config Client

Each service still needs a small local `application.yml`.

Do not move these bootstrap properties into remote configuration because the service needs them to know:

1. Its application name.
2. Where Config Server is located.

Example for Student Service:

```yaml
spring:
  application:
    name: student-service

  config:
    import: configserver:http://localhost:8888
```

Example for API Gateway:

```yaml
spring:
  application:
    name: api-gateway

  config:
    import: configserver:http://localhost:8888
```

Example for Discovery Service:

```yaml
spring:
  application:
    name: service-discovery

  config:
    import: configserver:http://localhost:8888
```

Example for another business service:

```yaml
spring:
  application:
    name: warehouse-service

  config:
    import: configserver:http://localhost:8888
```

---

# 10. How Configuration Is Loaded

Example: `student-service`

Local configuration:

```yaml
spring:
  application:
    name: student-service

  config:
    import: configserver:http://localhost:8888
```

Startup flow:

```text
1. Student Service starts.

2. It reads:
   spring.application.name = student-service

3. It reads:
   spring.config.import = configserver:http://localhost:8888

4. It contacts Config Server on port 8888.

5. Config Server resolves:
   config/application.yml
   config/student-service.yml

6. Config Server returns the combined Environment.

7. Student Service starts using the resulting configuration.
```

Conceptually:

```text
config/application.yml
        +
config/student-service.yml
        =
Student Service configuration
```

If both files contain the same property, the more service-specific configuration should be used for service-specific overrides.

---

# 11. Database Configuration Rule

Database configuration should be centralized in the service-specific YAML file.

Do **not** duplicate database configuration inside the local `application.yml` of a Config Client.

Example:

```text
config-service/
`-- src/main/resources/config/
    `-- student-service.yml
```

Example `student-service.yml`:

```yaml
server:
  port: 5052

  # Allows correct forwarding behavior behind API Gateway
  forward-headers-strategy: framework

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/student_management
    username: postgres
    password: ${STUDENT_DB_PASSWORD:12345678}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: update

    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

        jdbc:
          time_zone: Asia/Ho_Chi_Minh

eureka:
  instance:
    prefer-ip-address: true

springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs
```

For team development, avoid committing real production secrets.

Prefer environment variables:

```yaml
spring:
  datasource:
    username: ${STUDENT_DB_USERNAME:postgres}
    password: ${STUDENT_DB_PASSWORD}
```

Do not commit:

- Production database passwords.
- GitHub/GitLab access tokens.
- JWT private secrets.
- Cloud credentials.
- API keys.

---

# 12. Discovery Service Configuration

Centralized file:

```text
config/service-discovery.yml
```

Example:

```yaml
server:
  port: 8761

eureka:
  client:
    register-with-eureka: false
    fetch-registry: false

  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 60000
```

Local Discovery `application.yml`:

```yaml
spring:
  application:
    name: service-discovery

  config:
    import: configserver:http://localhost:8888
```

The Discovery Server does not register itself as a normal Eureka Client.

---

# 13. API Gateway Configuration

Centralized file:

```text
config/api-gateway.yml
```

Example:

```yaml
server:
  port: 8080

spring:
  cloud:
    gateway:
      server:
        webflux:
          discovery:
            locator:
              enabled: true
              lower-case-service-id: true

              predicates:
                - name: Path
                  args:
                    pattern: "'/${app.api-prefix}/' + serviceId + '/**'"

              filters:
                - name: RewritePath
                  args:
                    regexp: "'/${app.api-prefix}/' + serviceId + '/?(?<remaining>.*)'"
                    replacement: "'/${remaining}'"

app:
  api-prefix: api

springdoc:
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
    url: /swagger-docs/all
    disable-swagger-default-url: true
```

Local Gateway `application.yml`:

```yaml
spring:
  application:
    name: api-gateway

  config:
    import: configserver:http://localhost:8888
```

The Eureka URL can come from the common:

```text
config/application.yml
```

---

# 14. Swagger / OpenAPI Strategy

Swagger is intentionally **not** part of the common `config/application.yml`.

The project has two different Swagger roles:

```text
API Gateway
    -> WebFlux Swagger
    -> owns the central Swagger UI / aggregated documentation view

Business Services
    -> WebMVC Swagger
    -> generate their own /v3/api-docs
```

Discovery Service and Config Service normally do not need Swagger.

---

## 14.1 API Gateway Swagger

API Gateway uses Spring WebFlux, so it must use:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

Its Swagger configuration belongs in:

```text
config/api-gateway.yml
```

Example:

```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs

  swagger-ui:
    enabled: true
    path: /swagger-ui.html

    # Aggregated Swagger endpoint handled by the Gateway
    url: /swagger-docs/all

    disable-swagger-default-url: true
```

The Gateway is the place where the project exposes the main Swagger UI.

Conceptually:

```text
Business Service API Docs
        |
        | /v3/api-docs
        v
   API Gateway
        |
        | /swagger-docs/all
        v
   Swagger UI
```

---

## 14.2 Business Service Swagger

Normal REST business services use Spring MVC.

Examples:

```text
student-service
order-service
warehouse-service
shipment-service
inventory-service
```

Each MVC service should use:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

Its Springdoc configuration belongs in its own service configuration file.

Example:

```text
config/student-service.yml
```

```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs

  # Recommended when the team uses only the central Gateway Swagger UI
  swagger-ui:
    enabled: false
```

Another example:

```text
config/order-service.yml
```

```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs

  swagger-ui:
    enabled: false
```

This means each business service still generates its OpenAPI document:

```text
/v3/api-docs
```

but does not need to expose a separate Swagger UI when the project uses the Gateway as the central Swagger UI.

---

## 14.3 Why Swagger Is Not Put in `config/application.yml`

Do not use this as common configuration:

```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs
```

inside:

```text
config/application.yml
```

because not every application has the same Swagger role.

Correct separation:

```text
config/application.yml
    -> shared Eureka URL and other truly common properties

config/api-gateway.yml
    -> WebFlux Gateway configuration
    -> central Swagger UI
    -> aggregated Swagger configuration

config/student-service.yml
    -> Student database/JPA configuration
    -> WebMVC /v3/api-docs configuration

config/order-service.yml
    -> Order database/JPA configuration
    -> WebMVC /v3/api-docs configuration

config/service-discovery.yml
    -> Eureka Server configuration
    -> no Swagger by default
```

---

## 14.4 Swagger Dependency Mapping

| Application Type | Dependency | Swagger UI |
|---|---|---|
| API Gateway | `springdoc-openapi-starter-webflux-ui` | Enabled, central UI |
| MVC Business Service | `springdoc-openapi-starter-webmvc-ui` | Usually disabled when Gateway aggregates docs |
| Discovery Service | None by default | Not required |
| Config Service | None by default | Not required |

Important:

```text
Gateway       -> WebFlux
Business API  -> WebMVC
```

Do not use the WebMVC Springdoc starter in API Gateway.

Do not use the WebFlux Springdoc starter in a normal Spring MVC service.

---

## 14.5 Final Swagger Flow

Example with Student and Order services:

```text
Student Controller
       |
       v
Springdoc WebMVC
       |
       v
/v3/api-docs
       \
        \
         +--------------------+
                              |
Order Controller              |
       |                      |
       v                      |
Springdoc WebMVC              |
       |                      |
       v                      |
/v3/api-docs -----------------+
                              |
                              v
                        API Gateway
                        Springdoc WebFlux
                              |
                              v
                     /swagger-docs/all
                              |
                              v
                     /swagger-ui.html
```

The important rule is:

> Business services generate API documentation.  
> API Gateway provides the central Swagger UI.


# 15. Eureka Strategy

Common Eureka Client URL:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

This is a good candidate for:

```text
config/application.yml
```

because many Config Client services use the same Discovery Server.

Business services normally also use:

```yaml
eureka:
  instance:
    prefer-ip-address: true
```

This may remain service-specific unless the team decides that every Eureka Client uses the exact same setting.

---

# 16. Recommended Startup Order

Start the project in this order:

```text
1. Database / PostgreSQL
       |
       v
2. Config Service :8888
       |
       v
3. Discovery Service :8761
       |
       v
4. API Gateway :8080
       |
       v
5. Business Services
```

Why?

- Config Clients need Config Server to obtain centralized configuration.
- Eureka Clients need Discovery Service for registration/discovery.
- Gateway needs service discovery to route to registered services.
- Business services may require databases before completing startup.

---

# 17. Testing Config Server

After Config Service starts on port `8888`, test configuration directly.

Student:

```text
http://localhost:8888/student-service/default
```

Gateway:

```text
http://localhost:8888/api-gateway/default
```

Discovery:

```text
http://localhost:8888/service-discovery/default
```

If Config Server is working correctly, the JSON response should contain `propertySources` from files such as:

```text
classpath:/config/student-service.yml
classpath:/config/application.yml
```

This confirms that both common and service-specific configuration were loaded.

---

# 18. Adding a New Business Service

Example: `warehouse-service`

## Step 1 — Create the service project

Recommended location:

```text
be-project-nexus/warehouse-service/
```

## Step 2 — Inherit the parent POM

```xml
<parent>
    <groupId>com.group5</groupId>
    <artifactId>nexus-parent</artifactId>
    <version>1.0.0</version>
    <relativePath>../lib/pom.xml</relativePath>
</parent>
```

## Step 3 — Set the service artifact

```xml
<groupId>com.group5</groupId>
<artifactId>warehouse-service</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

## Step 4 — Add only required dependencies

Typical business service:

```text
Spring Web MVC
Eureka Client
Config Client
Swagger MVC
Lombok
```

If it owns a PostgreSQL database:

```text
Spring Data JPA
PostgreSQL Driver
```

## Step 5 — Create local `application.yml`

```yaml
spring:
  application:
    name: warehouse-service

  config:
    import: configserver:http://localhost:8888
```

## Step 6 — Create centralized configuration

```text
config-service/src/main/resources/config/warehouse-service.yml
```

Example:

```yaml
server:
  port: 5053

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/warehouse_management
    username: ${WAREHOUSE_DB_USERNAME:postgres}
    password: ${WAREHOUSE_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: update

eureka:
  instance:
    prefer-ip-address: true
```

## Step 7 — Verify Config Server

Open:

```text
http://localhost:8888/warehouse-service/default
```

Make sure both common and service-specific configuration are returned.

---

# 19. Rules Every Developer Must Follow

1. **Do not duplicate Spring Boot / Java / Spring Cloud version configuration in every service.**  
   Use `lib/pom.xml`.

2. **Do not put service-specific dependencies into the parent POM.**

3. **Every Config Client must include:**

   ```xml
   <dependency>
       <groupId>org.springframework.cloud</groupId>
       <artifactId>spring-cloud-starter-config</artifactId>
   </dependency>
   ```

4. **Only Config Service should use:**

   ```xml
   <dependency>
       <groupId>org.springframework.cloud</groupId>
       <artifactId>spring-cloud-config-server</artifactId>
   </dependency>
   ```

5. **Every Config Client must keep a local `spring.application.name`.**

6. **The service name must match the centralized YAML filename.**

   ```text
   student-service -> student-service.yml
   api-gateway -> api-gateway.yml
   service-discovery -> service-discovery.yml
   ```

7. **Every Config Client must import Config Server:**

   ```yaml
   spring:
     config:
       import: configserver:http://localhost:8888
   ```

8. **Do not duplicate database configuration in local service `application.yml`.**  
   Keep it in `<service-name>.yml` on Config Server.

9. **Do not put every setting into `config/application.yml`.**  
   Only genuinely shared settings belong there.

10. **Do not commit secrets.**

11. **API Gateway uses WebFlux dependencies.**

12. **Normal REST business services use Spring MVC dependencies.**

13. **Swagger dependency must match the web stack:**

    ```text
    Gateway       -> springdoc WebFlux
    Business API  -> springdoc WebMVC
    ```

    Swagger/Springdoc configuration must **not** be placed in the common `config/application.yml`.

    ```text
    config/api-gateway.yml
        -> central Swagger UI / WebFlux configuration

    config/<business-service>.yml
        -> /v3/api-docs / WebMVC configuration
    ```

14. **Discovery Service is Eureka Server, not a normal Eureka Client.**

15. **A service should remain independently buildable and runnable even though it shares parent dependency management and centralized configuration.**

---

# 20. Common vs Service-Specific Configuration

Use this rule when deciding where to place a property.

## Put in `config/application.yml` when:

The same value is intentionally shared by many/all Config Clients.

Example:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

## Put in `<service-name>.yml` when:

The property belongs to one service.

Examples:

```text
server.port
database URL
database name
JPA behavior
Gateway routes
Swagger Gateway settings
service-specific timeout
Redis database index
service-specific external API configuration
```

---

# 21. Key Mental Model

## Maven

```text
lib/pom.xml
     |
     +--> version/dependency management
     |
     +--> config-service
     +--> service-discovery
     +--> api-gateway
     +--> business services
```

## Configuration

```text
Config Service :8888
     |
     +--> config/application.yml
     |        COMMON
     |
     +--> config/student-service.yml
     |        STUDENT ONLY
     |
     +--> config/api-gateway.yml
     |        GATEWAY ONLY
     |
     `--> config/service-discovery.yml
              DISCOVERY ONLY
```

## Config Client Resolution

```text
spring.application.name
        |
        v
Config Server
        |
        +--> application.yml
        |
        `--> <application-name>.yml
```

## Server vs Client

```text
spring-cloud-config-server
        =
"I provide configuration."

spring-cloud-starter-config
        =
"I retrieve configuration."
```

---

# 22. Quick Developer Checklist

Before opening a merge request for a new service, verify:

```text
[ ] Service inherits ../lib/pom.xml
[ ] artifactId is correct
[ ] spring.application.name matches the service name
[ ] Config Client dependency is present
[ ] Eureka Client dependency is present when required
[ ] Correct Spring MVC/WebFlux stack is used
[ ] Correct Swagger dependency is used
[ ] Gateway Swagger configuration is in `api-gateway.yml`
[ ] Business-service Swagger configuration is in that service's own YAML
[ ] Swagger/Springdoc is not placed in common `config/application.yml`
[ ] Database dependencies are included only when needed
[ ] Local application.yml imports http://localhost:8888
[ ] <service-name>.yml exists in Config Service
[ ] Database configuration is centralized
[ ] No password/token/secret is committed
[ ] Config Server endpoint returns the expected configuration
[ ] Service registers with Eureka when applicable
[ ] Service is reachable through API Gateway when applicable
```

---

## Final Principle

The project uses two different forms of centralization:

```text
Maven Parent POM
    -> centralizes build and dependency management

Spring Cloud Config
    -> centralizes runtime application configuration
```

Neither mechanism should remove service ownership.

Each microservice must still contain its own:

- Business logic.
- Controllers/API.
- Domain model.
- Service-specific dependencies.
- Service-specific runtime configuration.
- Database ownership/configuration when applicable.

The shared infrastructure exists to reduce duplication, not to turn the system into one tightly coupled application.
