FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /build
COPY pom.xml .
COPY backend backend
COPY gateway gateway
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl backend -am -DskipTests package
FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0
WORKDIR /app
RUN groupadd --gid 10001 assets && useradd --uid 10001 --gid assets --no-create-home assets
COPY --from=build /build/backend/target/assets-backend-1.0.0-SNAPSHOT.jar /app/application.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/application.jar"]
