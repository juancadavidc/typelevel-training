# Build and run the pricing API.
#
# Two stages so the runtime image carries a JRE and a jar, not sbt, coursier caches and
# the Scala compiler — the difference is roughly an order of magnitude in image size.

FROM sbtscala/scala-sbt:eclipse-temurin-21.0.2_13_1.10.7_3.6.2 AS build

WORKDIR /build

# Dependency resolution is the slow part and changes far less often than the sources, so
# it gets its own layer: editing a .scala file re-runs the compile, not the download.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt ./project/
RUN sbt update

COPY domain/src ./domain/src
COPY service/src ./service/src
COPY lambda/src ./lambda/src
RUN sbt "service/assembly"

FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# A non-root user because the container has no reason to run as root, and Fargate task
# definitions are reviewed for exactly this.
RUN addgroup -S pricing && adduser -S pricing -G pricing
USER pricing

COPY --from=build /build/service/target/scala-3.8.4/pricing-service-assembly.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
