#!/usr/bin/env bash
# sd1~sd6 통합 시나리오를 차례로 돌린다. 장애를 일으키는 sd3(worker-5 정지), sd5(주 브로커 정지)는 마지막에 둔다.
#   docker compose up -d --build --wait && scripts/scenarios/run-all.sh
#   MODE=local scripts/scenarios/run-all.sh   (scripts/local-run.sh start 로 띄운 경우)
# sd7(유형별 대기열 인출)은 broker 설정을 켜고 새로 띄운 뒤 따로 돌린다:
#   BROKER_TYPED_PULL=true BROKER_TYPED_PULL_DEADLINE_SEC=60 BROKER_TYPED_PULL_MARGIN_SEC=20 docker compose up -d --wait
#   SCENARIOS=sd7 scripts/scenarios/run-all.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
for sd in ${SCENARIOS:-sd1 sd2 sd4 sd6 sd3 sd5}; do
  bash "$DIR/$sd.sh"
done
printf '\033[32m모든 시나리오 통과\033[0m\n'
