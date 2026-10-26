FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp verify

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system stockroom && useradd --system --gid stockroom stockroom
COPY --from=build /workspace/target/demo-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build /workspace/target/classes/com/example/demo/inventory/HealthProbe.class /app/health/com/example/demo/inventory/HealthProbe.class
USER stockroom
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
