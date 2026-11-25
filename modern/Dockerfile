# Runtime image for the modern MaSyMoS service.
#
# The Spring Boot fat jar is NOT built inside this image. Build it first from
# modern/ (Maven outside Docker):
#
#   mvn -q verify
#   docker build -t masymos-service:local .
#
# The expected artifact is:
#   masymos-service/target/masymos-service-2.0.0-SNAPSHOT.jar
#
# .dockerignore excludes **/target/* and re-includes exactly that jar, so
# `docker build` fails fast with a missing-file error if it was not built.
#
# Optional runtime tuning (pass at run time, never bake secrets here):
#   JAVA_TOOL_OPTIONS="-Xms256m -Xmx1g"      # heap sizing
#   MASYMOS_NEO4J_ENABLED / _URI / _USERNAME / _PASSWORD / _DATABASE
#     are supplied as environment variables by compose.yaml.

FROM eclipse-temurin:25-jre

# Unprivileged runtime user.
RUN groupadd --system --gid 10001 masymos \
 && useradd --system --uid 10001 --gid masymos --no-create-home --shell /usr/sbin/nologin masymos

WORKDIR /app

COPY masymos-service/target/masymos-service-2.0.0-SNAPSHOT.jar app.jar

RUN chown -R masymos:masymos /app

EXPOSE 8080

USER masymos

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
