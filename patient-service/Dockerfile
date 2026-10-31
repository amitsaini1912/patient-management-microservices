FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .

RUN mvn dependency:go-offline -B

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jdk AS runner

WORKDIR /app

COPY --from=builder ./app/target/patient-service-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 4000


# Day 20a (ADR 0019): OpenTelemetry Java agent, checksum-pinned. Off by default - set OTEL_SDK_DISABLED=false and
# OTEL_EXPORTER_OTLP_ENDPOINT (e.g. http://jaeger:4318) to export traces; OTEL_SERVICE_NAME defaults below.
ADD --checksum=sha256:d05f6e36fac8db629263a6aaec2841cc934d064d7b19bfe38425b604b8b54926 \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.10.0/opentelemetry-javaagent-2.10.0.jar \
    /otel/opentelemetry-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar" \
    OTEL_SDK_DISABLED=true OTEL_SERVICE_NAME=patient-service OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none

ENTRYPOINT ["java", "-jar", "app.jar"]