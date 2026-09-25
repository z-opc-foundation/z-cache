FROM maven:3.9.9-eclipse-temurin-8 AS builder
ARG M2_REPO=/root/.m2
WORKDIR /build
COPY ./pom.xml ./pom.xml
COPY ./z-cache ./z-cache
COPY ./z-cache/.build-context/settings.xml /tmp/settings.xml
RUN --mount=type=bind,from=maven-m2,source=/repository,target=/root/.m2/repository,rw \
    mvn -s /tmp/settings.xml -f z-cache/pom.xml -N install -DskipTests && \
    mvn -s /tmp/settings.xml -f z-cache/pom.xml -pl z-cache-server -am clean package -DskipTests

FROM eclipse-temurin:8-jre
RUN useradd --system --uid 10001 zcache
WORKDIR /app
COPY --from=builder /build/z-cache/z-cache-server/target/z-cache-server.jar /app/z-cache-server.jar
RUN chown -R zcache:zcache /app
USER 10001

ENV ZCACHE_HOST=0.0.0.0 \
    ZCACHE_PORT=6379 \
    ZCACHE_MAX_ENTRIES=0 \
    ZCACHE_PASSWORD="" \
    ZCACHE_PASSWORD_FILE="" \
    ZCACHE_DATA_DIR=""
EXPOSE 6379

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
  CMD sh -c 'if [ -n "$ZCACHE_PASSWORD_FILE" ]; then exec java -cp /app/z-cache-server.jar com.zifang.z.cache.server.HealthCheck --port "$ZCACHE_PORT" --password-file "$ZCACHE_PASSWORD_FILE"; else exec java -cp /app/z-cache-server.jar com.zifang.z.cache.server.HealthCheck --port "$ZCACHE_PORT" --password "$ZCACHE_PASSWORD"; fi'

CMD ["sh", "-c", "set -- --host \"$ZCACHE_HOST\" --port \"$ZCACHE_PORT\" --max-entries \"$ZCACHE_MAX_ENTRIES\"; if [ -n \"$ZCACHE_PASSWORD_FILE\" ]; then set -- \"$@\" --password-file \"$ZCACHE_PASSWORD_FILE\"; fi; if [ -n \"$ZCACHE_DATA_DIR\" ]; then set -- \"$@\" --data-dir \"$ZCACHE_DATA_DIR\"; fi; exec java -jar /app/z-cache-server.jar \"$@\""]
