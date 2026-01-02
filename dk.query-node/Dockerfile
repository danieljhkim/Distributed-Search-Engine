# Build stage
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app
COPY . .
RUN mvn -q -DskipTests clean package -pl dk.query-node -am

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Update the jar path/name to match your module output
COPY --from=build /app/dk.query-node/target/dk-query-node.jar app.jar

EXPOSE 6000 6100
ENTRYPOINT ["java","-jar","app.jar"]

