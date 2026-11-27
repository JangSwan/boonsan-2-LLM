#!/usr/bin/env bash
# mock worker 2대(이 서버) ↔ GPU 서버의 real worker를 바꾼다. 같은 모드로 다시 실행해도 결과가 같다.
#   scripts/cloud/mode.sh mock
#   scripts/cloud/mode.sh real     # 먼저 .env의 REAL_WORKER_URLS에 GPU 서버 주소를 넣는다
source "$(dirname "$0")/lib.sh"
require_env
MODE=${1:-}
MOCK_URLS="worker-1=http://worker-1:8091,worker-2=http://worker-2:8092"

case "$MODE" in
  mock)
    env_set LLM_MODE mock
    env_set COMPOSE_PROFILES mock-workers
    env_set WORKER_URLS "$MOCK_URLS"
    env_set BROKER_CONCURRENCY_CAP "$(env_get MOCK_CONCURRENCY_CAP || true)"
    env_set MODEL_ACTIVE_STAGE U0
    ;;
  real)
    urls=$(env_get REAL_WORKER_URLS)
    if [ -z "$urls" ]; then
      echo ".env의 REAL_WORKER_URLS에 GPU 서버 주소를 넣으세요. 예: worker-1=http://172.31.45.216:8091,worker-2=http://172.31.40.10:8092" >&2
      exit 1
    fi
    env_set LLM_MODE real
    env_set COMPOSE_PROFILES ""
    env_set WORKER_URLS "$urls"
    env_set BROKER_CONCURRENCY_CAP "$(env_get REAL_CONCURRENCY_CAP)"
    env_set MODEL_ACTIVE_STAGE "$(env_get REAL_ACTIVE_STAGE)"
    # 프로필이 꺼져도 이미 떠 있는 mock worker는 그대로 남으므로 직접 내린다
    compose --profile mock-workers stop worker-1 worker-2 >/dev/null 2>&1 || true
    compose --profile mock-workers rm -f worker-1 worker-2 >/dev/null 2>&1 || true
    ;;
  *)
    echo "사용법: $0 mock|real" >&2
    exit 1
    ;;
esac

# 바뀐 설정(WORKER_URLS: membership·monitoring, 상한: broker, 단계: model-manager)을 읽는 서비스만 다시 만들어진다
compose up -d
echo "모드: $(env_get LLM_MODE), 워커: $(env_get WORKER_URLS), 상한: $(env_get BROKER_CONCURRENCY_CAP), 단계: $(env_get MODEL_ACTIVE_STAGE)"
if [ "$MODE" = real ]; then
  echo "GPU 서버 합류는 GPU 서버에서 scripts/cloud/gpu-join.sh <worker-N> <이 서버 프라이빗 IP> (CPU 서버 쪽은 scripts/cloud/workers.sh)"
fi
