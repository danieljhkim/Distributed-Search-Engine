# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -q -DskipTests clean package -pl dk.index-node -am

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Update the jar path/name to match your module output
COPY --from=build /app/dk.index-node/target/dk-index-node.jar app.jar

EXPOSE 5000 5100
ENTRYPOINT ["java","--add-modules","jdk.incubator.vector","-jar","app.jar"]

