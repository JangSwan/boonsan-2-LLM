# 모든 앱이 같은 이미지 틀을 쓴다. jar는 먼저 빌드한다: ./gradlew build (또는 bootJar)
#   docker build -f docker/Dockerfile --build-arg MODULE=broker .
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ARG MODULE
WORKDIR /app
COPY ${MODULE}/build/libs/${MODULE}.jar /app/app.jar

# jdk.httpclient.keepalive.timeout: 쉬는 keep-alive 연결을 3초 뒤 닫는다. JDK 기본(30초)이 상대 서버(vLLM/uvicorn 약 5초)보다
# 길면, 서버가 먼저 닫은 연결을 다시 쓰다 "header parser received no bytes" 오류가 난다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:TieredStopAtLevel=1 -Xss512k -Djdk.httpclient.keepalive.timeout=3"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
