# Multi-stage build: compile with a full Gradle+JDK image, then run on a slim JRE-only image so
# the shipped container doesn't carry the whole Gradle distribution + build cache.
FROM gradle:8.13-jdk21 AS build
WORKDIR /home/gradle/backend
# Copy the Gradle files first so dependency resolution is cached across rebuilds that only touch
# application source.
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies || true
COPY src ./src
RUN gradle --no-daemon shadowJar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build --chown=1000:1000 /home/gradle/backend/build/libs/*-all.jar app.jar
EXPOSE 8080
# Run as a non-root, unprivileged UID/GID rather than the image's default root user: this
# process only serves HTTP and talks to Postgres over the network, so it never needs root, and a
# numeric UID/GID avoids a useradd/groupadd step in the runtime layer.
USER 1000:1000
ENTRYPOINT ["java", "-jar", "app.jar"]
