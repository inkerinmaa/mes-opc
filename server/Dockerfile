# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-24 AS build

WORKDIR /app
COPY pom.xml .
# Download dependencies first (layer cache)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:24-jre

WORKDIR /app
COPY --from=build /app/target/quarkus-app/ ./quarkus-app/

EXPOSE 4840

CMD ["java", "-jar", "quarkus-app/quarkus-run.jar"]
