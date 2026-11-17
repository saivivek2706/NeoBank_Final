# Build from repo root: docker build -f customer-service/Dockerfile .
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
COPY customer-service/pom.xml customer-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl customer-service -am dependency:go-offline
COPY customer-service customer-service
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl customer-service -am package -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
COPY --from=builder --chown=app:app /build/customer-service/target/*.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8086
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=6 CMD wget -qO- http://127.0.0.1:8086/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]