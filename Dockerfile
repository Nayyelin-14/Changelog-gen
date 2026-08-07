FROM docker.io/library/eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY service/mvnw service/pom.xml ./service/
COPY service/.mvn ./service/.mvn
COPY web-view/package.json web-view/package-lock.json ./web-view/
RUN cd service && ./mvnw dependency:go-offline -B
COPY service/src ./service/src
COPY web-view/ ./web-view/
# -Pprod enables Quinoa: builds the React app (web-view/) and embeds the Vite output into the
# JAR. Quinoa downloads its own Node (JDK image has none; apt's Node is too old for Vite 8).
# quarkus.rest.path scopes JAX-RS to /api (each resource's own @Path no longer includes it —
# see PipelineResource/AiBenchmarkResource/LatestRunChangelogPocResource/AzureDevOpsResource)
# so it stops claiming every path in the app. Without this, RESTEasy Reactive 404s a refreshed
# SPA deep link before Quinoa's enable-spa-routing ever gets a chance to serve the HTML shell —
# only shows up in this packaged jar, since quarkus:dev's Vite dev server does its own SPA
# fallback and never hits this conflict.
RUN cd service && ./mvnw package -Pprod -DskipTests \
    -Dquarkus.package.type=fast-jar \
    -Dquarkus.quinoa.ui-dir=../web-view \
    -Dquarkus.quinoa.build-dir=dist \
    -Dquarkus.quinoa.package-manager-install=true \
    -Dquarkus.quinoa.package-manager-install.node-version=22.14.0 \
    -Dquarkus.swagger-ui.always-include=true \
    -Dquarkus.rest.path=/api

FROM docker.io/library/eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app
WORKDIR /app
COPY --from=build /app/service/target/quarkus-app/lib/ ./lib/
COPY --from=build /app/service/target/quarkus-app/*.jar ./
COPY --from=build /app/service/target/quarkus-app/app/ ./app/
COPY --from=build /app/service/target/quarkus-app/quarkus/ ./quarkus/
RUN mkdir -p /data/changelog-composer && chown -R app:app /app /data/changelog-composer
EXPOSE 8080
USER app
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD curl -f http://localhost:8080/q/health || exit 1
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]