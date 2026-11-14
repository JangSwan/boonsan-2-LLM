#!/usr/bin/env bash
# sd6. 모델 교체와 이상 시 롤백 (QAS 5, 4): 설정 1곳 수정으로 한 대씩 교체, 끊긴 답변 0건, 품질 이상이면 U0로 롤백.
source "$(dirname "$0")/lib.sh"

status() { curl -s "$MM/api/model-manager/swaps/status"; }
wait_status() {
  local want=$1 timeout=$2
  for _ in $(seq 1 "$timeout"); do
    [ "$(status | jq -r .status)" = "$want" ] && return 0
    sleep 1
  done
  fail "교체 상태가 ${timeout}초 안에 $want가 되지 않음: $(status)"
}

log "sd6: 품질 게이트를 통과하지 못한 단계(SMALL)는 거부"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$MM/api/model-manager/swaps" \
  -H 'Content-Type: application/json' -d '{"model":"llama-3.1-8b","stage":"SMALL"}')
[ "$code" = 409 ] || fail "SMALL 교체 요청 응답 $code (409 기대)"

log "sd6: FP8 교체 요청, 교체 중에도 질문을 계속 보낸다"
curl -sf -X POST "$MM/api/model-manager/swaps" -H 'Content-Type: application/json' \
  -d '{"model":"llama-3.1-8b","stage":"FP8"}' >/dev/null || fail "FP8 교체 요청 실패"
start=$(date +%s)
i=0
while [ "$(status | jq -r .status)" != COMPLETED ] && [ $(( $(date +%s) - start )) -lt 180 ]; do
  i=$((i + 1)); ask "sd6-swap-$i" "교체 중 질문 $i" 90 &
  sleep 0.7
done
wait_status COMPLETED 5
elapsed=$(( $(date +%s) - start ))
wait
for n in $(seq 1 "$i"); do assert_completed "sd6-swap-$n"; done
[ "$(status | jq -r .activeStage)" = FP8 ] || fail "활성 단계가 FP8이 아님"
has_log model-manager 'event=model.switched' || fail "model.switched 로그 없음"
pass "sd6 교체: ${elapsed}초 만에 FP8 확정 (10분 이내), 교체 중 질문 ${i}건 끊김 없이 완료"

log "sd6: 운영 중 품질 이상 주입 → U0 롤백"
version=$(curl -s "$MM/api/model-manager/ratio" | jq -r .activeVersion)
for n in 1 2 3 4 5; do
  curl -sf -X POST "$MON/api/monitoring/quality-samples" -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"sample-$n\",\"versionTag\":\"$version\",\"score\":0.2}" >/dev/null
done
wait_status ROLLING_BACK 10
j=0
start=$(date +%s)
while [ "$(status | jq -r .status)" != ROLLED_BACK ] && [ $(( $(date +%s) - start )) -lt 180 ]; do
  j=$((j + 1)); ask "sd6-rb-$j" "롤백 중 질문 $j" 90 &
  sleep 0.7
done
wait_status ROLLED_BACK 5
wait
for n in $(seq 1 "$j"); do assert_completed "sd6-rb-$n"; done
[ "$(status | jq -r .activeStage)" = U0 ] || fail "롤백 후 활성 단계가 U0가 아님"
has_log model-manager 'event=model.rolled-back|event=model.rollback-started' || fail "롤백 로그 없음"
pass "sd6 롤백: 품질 이상 감지 후 같은 롤링 절차로 U0 복귀, 롤백 중 질문 ${j}건 완료"
