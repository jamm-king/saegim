FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/api-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
