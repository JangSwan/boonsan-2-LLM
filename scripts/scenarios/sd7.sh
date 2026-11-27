#!/usr/bin/env bash
# sd7. 유형별 대기열 인출 (QAS 1 실측 후 보완, 비평 QAS1 제안 채택). broker를 BROKER_TYPED_PULL=true로 띄운 뒤에만 돌린다.
# 긴 입력이 공용 큐에 잔뜩 밀린 뒤 짧은 질문이 오면, 짧은 질문이 밀린 긴 입력보다 먼저 꺼내져 첫 토큰을 받는다.
# 도착 순서(꺼짐)였다면 짧은 질문은 밀린 긴 입력이 다 시작된 뒤에야 시작한다.
source "$(dirname "$0")/lib.sh"

LONGS=${SD7_LONGS:-120}
DEADLINE_MS=${SD7_LONG_DEADLINE_MS:-60000}

log "sd7: 긴 입력(약 4,000토큰) ${LONGS}건 사이사이에 짧은 질문을 섞어 한꺼번에 보낸다 (긴 입력 5건마다 짧은 질문 1건)"
shorts=0
for i in $(seq 1 "$LONGS"); do
  ask_timed "sd7-L$i" "$(long_text 340)" 180
  if [ $((i % 5)) -eq 0 ]; then
    shorts=$((shorts + 1))
    ask_timed "sd7-S$shorts" "짧은 질문 $shorts" 120
  fi
done
wait

has_log broker-primary 'event=request.enqueued .*line=long' || fail "broker가 긴 요청 줄로 분류하지 않음 (BROKER_TYPED_PULL=true로 띄웠는지 확인)"
for i in $(seq 1 "$LONGS"); do assert_completed "sd7-L$i"; done
for i in $(seq 1 "$shorts"); do assert_completed "sd7-S$i"; done

# broker 로그에서 이 시나리오 요청의 적재·인출 순서를 본다. 짧은 요청이 인출될 때, 그보다 먼저 적재되어 아직 기다리는
# 긴 요청이 있으면 "앞지르기"다. 도착 순서(꺼짐)라면 앞지르기는 0이다.
svc_logs broker-primary >"$OUT/sd7-broker.log"
read -r overtakes long_max < <(python3 - "$OUT" "$LONGS" "$shorts" <<'PY'
import json, re, sys, glob, os
out, longs, shorts = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
kind = {}
for name, n, k in (("L", longs, "long"), ("S", shorts, "short")):
    for i in range(1, n + 1):
        with open(os.path.join(out, f"sd7-{name}{i}.sse"), encoding="utf-8") as f:
            for line in f:
                if line.startswith("data:") and '"requestId"' in line:
                    kind[json.loads(line[5:])["requestId"]] = k
                    break
order, waiting, overtakes = 0, {}, 0
enq = re.compile(r"event=request\.enqueued requestId=(\S+)")
iss = re.compile(r"event=lease\.issued requestId=(\S+)")
for line in open(os.path.join(out, "sd7-broker.log"), encoding="utf-8", errors="replace"):
    m = enq.search(line)
    if m and m.group(1) in kind:
        order += 1
        waiting[m.group(1)] = order
        continue
    m = iss.search(line)
    if m and m.group(1) in waiting:
        rid = m.group(1)
        at = waiting.pop(rid)
        if kind[rid] == "short" and any(kind[o] == "long" and t < at for o, t in waiting.items()):
            overtakes += 1
firsts = [int(open(os.path.join(out, f"sd7-L{i}.first")).read()) for i in range(1, longs + 1)]
print(overtakes, max(firsts))
PY
)
log "밀린 긴 입력을 앞질러 꺼내진 짧은 질문 ${overtakes}/${shorts}건, 긴 입력 첫 토큰 최대 ${long_max}ms"
[ "$overtakes" -ge 5 ] || fail "짧은 질문이 먼저 적재된 긴 입력을 앞지르지 않음 (${overtakes}건 < 5)"
[ "$long_max" -lt "$DEADLINE_MS" ] || fail "긴 입력 첫 토큰 ${long_max}ms ≥ 기한 ${DEADLINE_MS}ms (밀려서 굶음)"
pass "sd7: 짧은 질문 ${overtakes}/${shorts}건이 밀린 긴 입력을 앞질러 꺼내지고, 긴 입력은 모두 기한 안에 시작 (최대 ${long_max}ms)"
