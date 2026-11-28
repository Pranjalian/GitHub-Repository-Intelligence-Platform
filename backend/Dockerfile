# syntax=docker/dockerfile:1
# Backend image for running the app locally with Docker Compose (see compose.yaml).
# Tests are not run here: they run with `./mvnw clean verify` and in CI, without Docker.

# ---- Build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# A Windows checkout may have CRLF line endings despite .gitattributes.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# Dependencies get their own layer, so they are cached until pom.xml changes. (A BuildKit cache mount is not
# used: Railway rejects `--mount=type=cache` without an id.)
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -Dmaven.test.skip=true \
 && cp "$(find target -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)" /app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre

# Semgrep CLI (pinned) so the Semgrep security analyzer is available without a host install.
# It only runs vendored local rules, with metrics and version checks off.
ARG SEMGREP_VERSION=1.186.0
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv curl \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -m venv /opt/semgrep \
 && /opt/semgrep/bin/pip install --no-cache-dir "semgrep==${SEMGREP_VERSION}" \
 && /opt/semgrep/bin/semgrep --version

# Non-root user. /app is its home (Semgrep writes its settings there); /workspace holds extracted snapshots.
RUN groupadd --system app \
 && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app /workspace \
 && chown app:app /app /workspace

ENV PATH="/opt/semgrep/bin:${PATH}" \
    LANG=C.UTF-8 \
    WORKSPACE_ROOT=/workspace \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

WORKDIR /app
COPY --from=build --chown=app:app /app.jar /app/app.jar
# The container starts as root only to fix the owner of the /workspace volume, then the entrypoint drops to the
# non-root "app" user (setpriv) before the JVM starts; the application never runs as root.
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0755 /app/docker-entrypoint.sh

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh", "java", "-jar", "/app/app.jar"]
