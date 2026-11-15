FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/lib

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends chromium && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /app/target/lib lib
ENV HOST=0.0.0.0 PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "app.jar:lib/*", "bot.Servidor"]
