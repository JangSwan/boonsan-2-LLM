#!/usr/bin/env bash
# 한 화면 상태 확인. 데모 날 "20분 전 결정"에도 쓴다: 모두 초록이면 라이브(Plan A), 아니면 B·C로.
#   scripts/cloud/status.sh
# 확인하는 것: 앱별 /actuator/health, redis, 합류한 워커 수(monitoring 지표), 시험 질문 1건의 첫 토큰
source "$(dirname "$0")/lib.sh"
require_env
H=http://localhost
GREEN=$'\e[32m'; RED=$'\e[31m'; DIM=$'\e[2m'; OFF=$'\e[0m'
fail=0
ok()  { printf '  %s✔%s %s\n' "$GREEN" "$OFF" "$1"; }
bad() { printf '  %s✘%s %s\n' "$RED" "$OFF" "$1"; fail=1; }

mode=$(env_get LLM_MODE)
echo "모드: $mode / 크기: $(env_get SERVER_SIZE)"

echo "앱 상태"
if compose exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; then ok "redis"; else bad "redis"; fi
for svc in broker-primary:8081 broker-replica:8082 membership-1:8101 membership-2:8102 model-manager:8110 \
           monitoring:8120 fake-aws:8130 front-gateway:8080 client:8140; do
  name=${svc%%:*}; port=${svc##*:}
  if curl -fs -m 3 "$H:$port/actuator/health" | grep -q UP; then ok "$name"; else bad "$name ($port)"; fi
done

echo "워커"
urls=$(env_get WORKER_URLS)
expected=$(printf '%s' "$urls" | tr ',' '\n' | grep -c . || true)
metrics=$(curl -fs -m 3 "$H:8120/api/monitoring/metrics" || true)
value() { printf '%s' "$metrics" | jq -r --arg n "$1" '[.[] | select(.name == $n) | .value][0] // empty' 2>/dev/null || true; }
responding=$(value workers.responding); responding=${responding%.*}
cap=$(value queue.concurrency-cap); cap=${cap%.*}
if [ -n "$responding" ] && [ "$responding" -ge "$expected" ]; then
  ok "응답하는 워커 $responding / $expected대 (상한 ${cap:-?})"
else
  bad "응답하는 워커 ${responding:-?} / $expected대 (WORKER_URLS: $urls)"
fi

echo "시험 질문"
start=$(date +%s%3N)
first=""
while IFS= read -r line; do
  if [[ -z "$first" && "$line" == *'"isFirst":true'* ]]; then
    first=$(( $(date +%s%3N) - start ))
    break
  fi
done < <(curl -sN -m 60 -X POST "$H:8080/api/front-gateway/questions" -H 'Content-Type: application/json' \
          -H 'Accept: text/event-stream' -d '{"text":"상태 확인: 1 더하기 1은?"}' 2>/dev/null || true)
if [ -n "$first" ]; then ok "첫 토큰 ${first}ms"; else bad "60초 안에 첫 토큰을 받지 못함"; fi

echo
if [ "$fail" = 0 ]; then
  printf '%s결과: 모두 정상%s %s(데모라면 라이브 진행)%s\n' "$GREEN" "$OFF" "$DIM" "$OFF"
else
  printf '%s결과: 문제 있음%s %s(데모라면 Plan B·C로)%s\n' "$RED" "$OFF" "$DIM" "$OFF"
fi
"$(dirname "$0")/info.sh" | sed -n '2,4p'
exit "$fail"
