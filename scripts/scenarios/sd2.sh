#!/usr/bin/env bash
# sd2. 스텝 예산과 Prefill 조각 실행 (QAS 2): 긴 입력 L이 Prefill 중이어도 짧은 요청 S가 먼저 첫 토큰을 받는다.
source "$(dirname "$0")/lib.sh"

log "sd2: 워커마다 긴 입력(약 4,000토큰) 1건씩, 그 직후 짧은 질문"
for i in 1 2 3 4 5; do
  ask_timed "sd2-L$i" "$(long_text 340)" 120
done
sleep 0.5
for i in 1 2 3 4 5; do
  ask_timed "sd2-S$i" "짧은 질문 $i" 60
done
wait

for i in 1 2 3 4 5; do
  assert_completed "sd2-L$i"
  assert_completed "sd2-S$i"
done
short_max=$(cat "$OUT"/sd2-S*.first | sort -n | tail -1)
long_min=$(cat "$OUT"/sd2-L*.first | sort -n | head -1)
log "짧은 질문 첫 토큰 최대 ${short_max}ms, 긴 입력 첫 토큰 최소 ${long_min}ms"
[ "$long_min" -ge 0 ] && [ "$short_max" -ge 0 ] || fail "측정값이 음수 (시계 문제)"
[ "$short_max" -lt "$long_min" ] || fail "짧은 질문이 긴 입력보다 늦게 첫 토큰을 받음"
[ "$short_max" -lt 3000 ] || fail "짧은 질문 첫 토큰 ${short_max}ms ≥ 3초"
pass "sd2: 짧은 질문 5건이 모두 긴 입력의 Prefill 완료 전에 첫 토큰을 받음 (최대 ${short_max}ms < ${long_min}ms)"
