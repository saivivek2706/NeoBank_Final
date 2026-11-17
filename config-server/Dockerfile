# Build from repo root: docker build -f config-server/Dockerfile -t neobank/config-server .
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY config-server/pom.xml config-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY transaction-service/pom.xml transaction-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY fraud-service/pom.xml fraud-service/pom.xml
COPY audit-service/pom.xml audit-service/pom.xml
COPY compliance-service/pom.xml compliance-service/pom.xml
# Resolve dependencies in their own layer so source edits don't re-download the world.
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl config-server -am dependency:go-offline
COPY config-server config-server
# Lint (checkstyle) and coverage (jacoco) run in CI, not in image builds - their config isn't in this build context.
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl config-server -am package -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as an unprivileged user: a compromised service must not get root inside the container.
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
COPY --from=builder --chown=app:app /build/config-server/target/*.jar app.jar
USER app

# Size the heap from the container's memory limit instead of the JVM's small defaults.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8888

# Lets `docker compose --wait` / depends_on: service_healthy and orchestrators know when the app is really up.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=6 \
  CMD wget -qO- http://127.0.0.1:8888/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
