FROM rust:1.91.0-bookworm AS rust-build
WORKDIR /work/backend/rust
COPY backend/rust/Cargo.toml backend/rust/Cargo.lock ./
COPY backend/rust/src ./src
COPY backend/rust/benches ./benches
COPY contracts/v1 /work/contracts/v1
RUN cargo build --jobs 2 --release --locked
FROM maven:3.9.12-eclipse-temurin-25 AS java-build
WORKDIR /work/backend/java
COPY backend/java/.mvn .mvn
COPY backend/java/pom.xml pom.xml
COPY backend/java/src src
COPY contracts/v1 /work/contracts/v1
RUN mvn -Dmaven.repo.local=/tmp/m2 -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=java-build /work/backend/java/target/signal-lab-api-0.1.0.jar /app/app.jar
COPY --from=rust-build /work/backend/rust/target/release/signal-lab-engine /app/signal-lab-engine
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN /app/signal-lab-engine --version
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
