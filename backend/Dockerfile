# One image for every PropertyOS service.
#   SERVICE=identity-service   run a single microservice (production topology)
#   SERVICE unset              run the whole platform in one container (low-cost demo hosting)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY platform-common platform-common
COPY api-gateway api-gateway
COPY identity-service identity-service
COPY leasing-service leasing-service
COPY billing-service billing-service
COPY maintenance-service maintenance-service
COPY workflow-service workflow-service
COPY ai-service ai-service
COPY document-service document-service
COPY platform-test-support platform-test-support
COPY platform-system-tests platform-system-tests
RUN mvn -q -B -Dmaven.test.skip=true package \
 && mkdir /jars \
 && for s in api-gateway identity-service leasing-service billing-service maintenance-service workflow-service ai-service document-service; do cp $s/target/app.jar /jars/$s.jar; done

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd -r -u 10001 propertyos
WORKDIR /app
COPY --from=build /jars /app
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh
USER propertyos
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
# Single-service mode: report healthy only when the service answers its own readiness probe.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=3 CMD curl -fs http://localhost:${PORT:-8080}/actuator/health/readiness || exit 1
ENTRYPOINT ["/app/docker-entrypoint.sh"]
