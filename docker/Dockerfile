# 모든 앱이 같은 이미지 틀을 쓴다. jar는 먼저 빌드한다: ./gradlew build (또는 bootJar)
#   docker build -f docker/Dockerfile --build-arg MODULE=broker .
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ARG MODULE
WORKDIR /app
COPY ${MODULE}/build/libs/${MODULE}.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:TieredStopAtLevel=1 -Xss512k"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
