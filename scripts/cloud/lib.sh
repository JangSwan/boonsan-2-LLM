#!/usr/bin/env bash
# scripts/cloud 공통 함수. 다른 스크립트가 source한다.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT/.env"

# docker 그룹에 들어가기 전(설치 직후 같은 접속)에는 sudo로 부른다
if docker info >/dev/null 2>&1; then DOCKER=(docker); else DOCKER=(sudo docker); fi

compose() {
  (cd "$ROOT" && "${DOCKER[@]}" compose -f docker-compose.yml -f docker-compose.cloud.yml --env-file "$ENV_FILE" "$@")
}

# .env 값 읽기: env_get KEY
env_get() {
  { grep -E "^$1=" "$ENV_FILE" || true; } | tail -1 | cut -d= -f2-
}

# .env 값 쓰기 (없으면 추가): env_set KEY VALUE
env_set() {
  local key=$1 value=$2
  if grep -qE "^$key=" "$ENV_FILE"; then
    # 값에 / & 가 들어갈 수 있으므로 구분자로 쓰지 않는 문자(|)를 쓴다
    local escaped=${value//\\/\\\\}
    escaped=${escaped//&/\\&}
    escaped=${escaped//|/\\|}
    sed -i "s|^$key=.*|$key=$escaped|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >>"$ENV_FILE"
  fi
}

require_env() {
  if [ ! -f "$ENV_FILE" ]; then
    echo ".env가 없습니다. 먼저 scripts/cloud/setup.sh를 실행하세요." >&2
    exit 1
  fi
}

# EC2 인스턴스 메타데이터(IMDSv2)의 IP 값. EC2가 아니거나 IP 형식이 아니면 빈 값
imds() {
  local token value
  token=$(curl -s -f -m 2 --noproxy '*' -X PUT http://169.254.169.254/latest/api/token \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null) || return 0
  value=$(curl -s -f -m 2 --noproxy '*' -H "X-aws-ec2-metadata-token: $token" \
    "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null) || return 0
  if [[ "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then printf '%s' "$value"; fi
}
