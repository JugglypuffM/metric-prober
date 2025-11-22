FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && \
    apt-get install -y curl && \
    curl -fsSL https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz | gzip -d > /usr/local/bin/cs && \
    chmod +x /usr/local/bin/cs && \
    cs setup -y --apps sbt --jvm 17

WORKDIR /app
COPY . .

RUN sbt clean docker:stage

EXPOSE 8889

CMD ["bin/metric-prober"]