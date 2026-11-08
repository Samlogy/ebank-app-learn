# Build context: backend/
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace
COPY . .
# Tests run in CI, not during the image build. The cache mount keeps downloads between builds.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar ebank-app/target/ebank-app-*.jar extract --layers --destination /extracted


    FROM eclipse-temurin:21.0.13_9-jre-alpine-3.24
RUN addgroup -S -g 10001 ebank && adduser -S -u 10001 -G ebank ebank
WORKDIR /app
# One image layer per Spring Boot layer, so code changes do not re-push the dependencies.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
EXPOSE 8080 8081
# No default profile: SPRING_PROFILES_ACTIVE must be set when the container starts.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "ebank-app-0.0.1-SNAPSHOT.jar"]
