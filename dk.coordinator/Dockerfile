# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -q -DskipTests clean package -pl dk.coordinator -am

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Update the jar path/name to match your module output
COPY --from=build /app/dk.coordinator/target/dk-coordinator.jar app.jar

EXPOSE 7000
ENTRYPOINT ["java","-jar","app.jar"]

