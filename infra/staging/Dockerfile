# Build from be-project-nexus, with --build-arg SERVICE=<module>.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY lib/ lib/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f lib/pom.xml -DskipTests install
COPY . .
ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 \
    case "$SERVICE" in config-service|discovery-service|api-gateway|user-service|catalog-service|commerce-service|auction-service|inventory-service|notification-service|payment-service|shipment-service|rating-service) ;; *) exit 2 ;; esac && \
    mvn -B -ntp -f "$SERVICE/pom.xml" -DskipTests package && \
    find "$SERVICE/target" -maxdepth 1 -name '*.jar' ! -name '*-tests.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 nexus && useradd --uid 10001 --gid nexus --no-create-home nexus
WORKDIR /app
COPY --from=build --chown=10001:10001 /app.jar /app/app.jar
COPY infra/staging/scripts/healthcheck.sh /usr/local/bin/nexus-healthcheck
RUN chmod 755 /usr/local/bin/nexus-healthcheck
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
