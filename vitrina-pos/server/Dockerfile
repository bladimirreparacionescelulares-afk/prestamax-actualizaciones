# Imagen del servidor de Vitrina POS.
# Se construye después de compilar: ./gradlew :server:installDist  (lo hace el flujo de GitHub)
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --home /app vitrina && mkdir -p /datos && chown vitrina /datos
COPY build/install/vitrina-server /app
USER vitrina
ENV DATA_DIR=/datos PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD curl -fs http://127.0.0.1:8080/salud || exit 1
ENTRYPOINT ["/app/bin/vitrina-server"]
