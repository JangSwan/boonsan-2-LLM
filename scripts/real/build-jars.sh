#!/usr/bin/env bash
# 서버에 Java를 설치하지 않고 Docker 안에서 앱 jar를 빌드한다 (docker/Dockerfile은 미리 빌드한 jar를 넣는다).
#   scripts/real/build-jars.sh            # 전체 (CPU 서버)
#   scripts/real/build-jars.sh worker     # worker만 (GPU 서버)
# 받은 의존성은 Docker 볼륨 llm-gradle-cache에 남아 두 번째부터 빨라진다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TASK=bootJar
if [ $# -ge 1 ]; then TASK=":$1:bootJar"; fi
docker run --rm -v "$ROOT":/src -v llm-gradle-cache:/root/.gradle -w /src \
  eclipse-temurin:21-jdk ./gradlew "$TASK" --no-daemon -q
ls -la "$ROOT"/*/build/libs/*.jar
