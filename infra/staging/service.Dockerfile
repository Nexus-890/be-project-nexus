FROM eclipse-temurin:21-jre

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin nexus \
    && mkdir -p /app/data \
    && chown 10001:10001 /app/data

WORKDIR /app
COPY target/*.jar /app/app.jar

USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
