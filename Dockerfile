# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY lib ./lib
COPY src ./src

# Ensure PostgreSQL JDBC driver is present
RUN if [ ! -f lib/postgresql-42.7.4.jar ]; then \
      wget -q -O lib/postgresql-42.7.4.jar https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar; \
    fi

RUN mkdir -p build && javac --release 21 -encoding UTF-8 -cp "lib/postgresql-42.7.4.jar" -d build src/*.java

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/build ./build
COPY --from=build /app/lib/postgresql-42.7.4.jar ./lib/postgresql-42.7.4.jar
COPY web ./web

EXPOSE 8080

CMD ["java", "-cp", "build:lib/postgresql-42.7.4.jar", "GymManagementServer"]
