#!/usr/bin/env bash
# 상시 서버(CPU 서버)에서 GPU 워커 목록(.env의 REAL_WORKER_URLS)을 관리하고 real 모드로 바꾼다. 여러 번 실행해도 결과가 같다.
#   scripts/cloud/workers.sh add worker-3 172.31.40.12     # GPU 서버 합류 스크립트(gpu-join.sh)가 마지막에 알려 준 줄
#   scripts/cloud/workers.sh remove worker-3
#   scripts/cloud/workers.sh list                          # 목록과 각 워커 응답 여부
#   scripts/cloud/workers.sh apply                         # real 모드로 바꾸고 상한을 정한다 (--cap auto | 숫자)
#
# 상한(--cap auto): GPU 5대면 4대분 = 4 × 슬롯(200, R3: 5대 운영·4대분 상한). 5대보다 적으면 대수 × 슬롯
#                    (QAS를 줄인 규모로 잴 때. 예: 2대 → 100). N+1 장애 시연을 적은 대수로 할 때는 --cap (대수-1)×슬롯.
source "$(dirname "$0")/lib.sh"
require_env
SLOTS=$(env_get REAL_WORKER_SLOTS); SLOTS=${SLOTS:-50}

# REAL_WORKER_URLS를 "id ip port" 줄로 펼친다
entries() {
  env_get REAL_WORKER_URLS | tr ',' '\n' | sed -n 's|^\(worker-[0-9]*\)=http://\([^:]*\):\([0-9]*\)$|\1 \2 \3|p' | sort
}
write_entries() {
  local joined
  joined=$(sort | awk 'NF==3 {printf "%s%s=http://%s:%s", (n++ ? "," : ""), $1, $2, $3}')
  env_set REAL_WORKER_URLS "$joined"
}
count() { entries | grep -c . || true; }

cmd=${1:-}
case "$cmd" in
  add)
    id=${2:-}; ip=${3:-}
    if ! [[ "$id" =~ ^worker-([1-5])$ ]] || ! [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "사용법: $0 add <worker-1 ~ worker-5> <GPU 서버 프라이빗 IP>" >&2; exit 1
    fi
    port=$((8090 + ${id#worker-}))
    { entries | awk -v id="$id" '$1 != id'; echo "$id $ip $port"; } | write_entries
    echo "추가: $id = http://$ip:$port (총 $(count)대). 다 붙였으면: $0 apply"
    ;;
  remove)
    id=${2:-}
    [ -n "$id" ] || { echo "사용법: $0 remove <worker-N>" >&2; exit 1; }
    entries | awk -v id="$id" '$1 != id' | write_entries
    echo "제거: $id (총 $(count)대). 반영하려면: $0 apply"
    ;;
  list)
    n=$(count)
    echo "GPU 워커 $n대 (모드: $(env_get LLM_MODE), 상한: $(env_get BROKER_CONCURRENCY_CAP))"
    while read -r id ip port; do
      [ -n "$id" ] || continue
      if curl -sf -m 3 "http://$ip:$port/actuator/health" | grep -q UP; then s="응답"; else s="응답 없음"; fi
      printf '  %-9s %s:%s  %s\n' "$id" "$ip" "$port" "$s"
    done < <(entries)
    ;;
  apply)
    cap_arg=auto
    if [ "${2:-}" = --cap ]; then cap_arg=${3:-auto}; fi
    n=$(count)
    if [ "$n" -eq 0 ]; then echo "GPU 워커가 없습니다. 먼저 $0 add <worker-N> <IP>" >&2; exit 1; fi
    if [ "$cap_arg" = auto ]; then
      if [ "$n" -ge 5 ]; then cap=$((4 * SLOTS)); else cap=$((n * SLOTS)); fi
    elif [[ "$cap_arg" =~ ^[0-9]+$ ]] && [ "$cap_arg" -gt 0 ]; then
      cap=$cap_arg
    else
      echo "--cap은 auto 또는 양의 정수" >&2; exit 1
    fi
    missing=0
    while read -r id ip port; do
      [ -n "$id" ] || continue
      if ! curl -sf -m 3 "http://$ip:$port/actuator/health" | grep -q UP; then
        echo "  주의: $id ($ip:$port) 응답 없음. 보안 그룹 llm-sg의 '8091-8095, 소스 = llm-control-sg' 규칙과 GPU 서버 상태를 확인하세요."
        missing=1
      fi
    done < <(entries)
    env_set REAL_CONCURRENCY_CAP "$cap"
    "$(dirname "$0")/mode.sh" real
    qas2=$(( (cap * 8 + 5) / 10 ))
    qas1=$(( cap * 5 ))
    echo
    echo "GPU $n대, 상한 $cap (워커당 슬롯 $SLOTS). 이 규모에 맞춘 QAS 부하 (보고서 4대분 = 상한 200 기준):"
    echo "  QAS2 동시 $qas2건 (상한의 80%) / QAS1 폭주 $qas1건 (1,000 × $cap/200)"
    if [ "$qas1" -ge 500 ] && [ "$(env_get SERVER_SIZE)" != measure ]; then
      echo "  측정 전에 이 서버를 m7i.2xlarge로 바꾸고 scripts/cloud/size.sh measure (docs/cloud-setup.md 3-3)"
    fi
    echo
    echo "  docker run --rm --network host --ulimit nofile=65535:65535 -v \"\$PWD\":/w -w /w eclipse-temurin:21-jdk \\"
    echo "    java scripts/loadtest/LoadTest.java --mode steady --concurrency $qas2 --duration-sec 120 --warmup-sec 20 --check --show-answers --csv qas2.csv"
    echo "  docker run --rm --network host --ulimit nofile=65535:65535 -v \"\$PWD\":/w -w /w eclipse-temurin:21-jdk \\"
    echo "    java scripts/loadtest/LoadTest.java --mode burst --requests $qas1 --check --answers qas1-answers.jsonl --csv qas1.csv"
    echo
    echo "상태 확인: scripts/cloud/status.sh"
    [ "$missing" = 0 ] || exit 2
    ;;
  *)
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
