#!/usr/bin/env bash
# GPU 서버 한 대를 상시 서버(CPU 서버)에 합류시킨다. GPU 서버의 레포 폴더에서 실행한다. 여러 번 실행해도 결과가 같다.
#   scripts/cloud/gpu-join.sh worker-3 172.31.40.10      # 워커 ID, 상시 서버의 프라이빗 IP
# 처음 띄운 GPU 서버(AMI llm-gpu-vllm-fp8)에서는:
#   git clone https://github.com/JangSwan/boonsan-2-LLM.git && cd boonsan-2-LLM && scripts/cloud/gpu-join.sh worker-1 <상시 서버 프라이빗 IP>
#
# 하는 일
#   1) AMI에 남은 예전 vllm 컨테이너에서 Hugging Face 토큰을 찾아 둔다 (없으면 입력받는다. 화면에 출력하지 않는다)
#   2) 예전 컨테이너를 지운다 (포트 8000과 GPU 메모리가 겹친다)
#   3) .env를 만든다/고친다: 워커 ID·포트, 상시 서버 IP, 실측으로 정한 값(FP8 + KV FP8, 슬롯 50, KV 0.2, B 2048)
#   4) worker 이미지를 빌드한다 (서버에 Java를 설치하지 않는다)
#   5) vLLM을 띄우고 준비될 때까지 기다린다 → worker를 띄운다
#   6) 상시 서버로 가는 연결과 worker 합류를 확인하고, 상시 서버에서 칠 명령을 알려 준다
source "$(dirname "$0")/lib.sh"

GREEN=$'\e[32m'; RED=$'\e[31m'; YELLOW=$'\e[33m'; DIM=$'\e[2m'; OFF=$'\e[0m'
ok()   { printf '  %s✔%s %s\n' "$GREEN" "$OFF" "$1"; }
bad()  { printf '  %s✘%s %s\n' "$RED" "$OFF" "$1"; }
step() { printf '\n%s[%s]%s %s\n' "$DIM" "$1" "$OFF" "$2"; }

WORKER_ID=${1:-}
CONTROL_IP=${2:-}
if ! [[ "$WORKER_ID" =~ ^worker-([1-5])$ ]]; then
  echo "사용법: $0 <worker-1 ~ worker-5> <상시 서버 프라이빗 IP>" >&2
  exit 1
fi
NUM=${BASH_REMATCH[1]}
PORT=$((8090 + NUM))
if ! [[ "$CONTROL_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "상시 서버의 프라이빗 IP를 주세요 (상시 서버에서 scripts/cloud/info.sh 마지막 줄). 예: 172.31.40.10" >&2
  exit 1
fi
GPU_COMPOSE=(-f docker-compose.real-gpu.yml --env-file "$ENV_FILE")
gpu_compose() { (cd "$ROOT" && "${DOCKER[@]}" compose "${GPU_COMPOSE[@]}" "$@"); }

step 1/6 "Hugging Face 토큰"
token=""
if [ -f "$ENV_FILE" ]; then token=$(env_get HF_TOKEN); fi
if [ -z "$token" ] && [ -n "${HF_TOKEN:-}" ]; then token=$HF_TOKEN; fi
if [ -z "$token" ] && "${DOCKER[@]}" inspect vllm >/dev/null 2>&1; then
  token=$("${DOCKER[@]}" inspect vllm --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | sed -n 's/^HF_TOKEN=//p' | head -1)
  if [ -n "$token" ]; then ok "예전 vllm 컨테이너에서 토큰을 찾았습니다"; fi
fi
if [ -z "$token" ]; then
  read -r -s -p "  Hugging Face Read 토큰(hf_...)을 붙여 넣고 엔터 (화면에 보이지 않음): " token
  echo
fi
if [[ "$token" != hf_* ]]; then
  bad "토큰 형식이 아닙니다 (hf_로 시작해야 함). 다시 실행하세요."
  exit 1
fi
ok "토큰 준비됨 (앞 3글자: ${token:0:3})"

step 2/6 "예전 vllm 컨테이너 정리"
if "${DOCKER[@]}" inspect vllm >/dev/null 2>&1; then
  "${DOCKER[@]}" update --restart=no vllm >/dev/null 2>&1 || true
  "${DOCKER[@]}" rm -f vllm >/dev/null
  ok "예전 컨테이너(vllm)를 지웠습니다"
else
  ok "예전 컨테이너 없음"
fi

step 3/6 ".env 작성"
if [ ! -f "$ENV_FILE" ]; then
  cp "$ROOT/.env.example" "$ENV_FILE"
  ok ".env.example에서 .env를 만들었습니다"
fi
chmod 600 "$ENV_FILE"
GPU_IP=$(imds local-ipv4)
if [ -z "$GPU_IP" ]; then GPU_IP=$(hostname -I 2>/dev/null | awk '{print $1}'); fi
env_set HF_TOKEN "$token"
env_set INSTANCE_ID "$WORKER_ID"
env_set SERVER_PORT "$PORT"
env_set CONTROL_IP "$CONTROL_IP"
env_set GPU_PRIVATE_IP "$GPU_IP"
# 1주차 실측으로 정한 값 (README "슬롯과 상한 정하기")
env_set VLLM_EXTRA_ARGS "--quantization fp8 --kv-cache-dtype fp8"
env_set WORKER_MAX_SLOTS 50
env_set WORKER_KV_GB_PER_REQUEST 0.2
env_set WORKER_B_MAX 2048
unset token
ok "$WORKER_ID / 포트 $PORT / 상시 서버 $CONTROL_IP / 이 서버 ${GPU_IP:-?}"

step 4/6 "worker 이미지 빌드 (처음 5~10분, 두 번째부터 빠름)"
if [ ! -f "$ROOT/worker/build/libs/worker.jar" ] || [ "${REBUILD:-0}" = 1 ] \
   || [ -n "$(find "$ROOT/worker/src" "$ROOT/common/src" -newer "$ROOT/worker/build/libs/worker.jar" -type f 2>/dev/null | head -1)" ]; then
  bash "$ROOT/scripts/real/build-jars.sh" worker >/dev/null
fi
gpu_compose build worker >/dev/null
ok "worker 이미지 준비됨"

step 5/6 "vLLM 기동 (AMI에서 처음 켠 서버는 가중치를 처음 읽느라 10~30분 걸릴 수 있음)"
gpu_compose up -d vllm >/dev/null
start=$(date +%s)
until curl -sf -m 3 http://127.0.0.1:8000/health >/dev/null 2>&1; do
  state=$(gpu_compose ps --format '{{.Status}}' vllm 2>/dev/null | head -1)
  last=$(gpu_compose logs --tail 1 --no-log-prefix vllm 2>/dev/null | tail -1 | cut -c1-90)
  printf '\r  %4ds  %-28s %s\e[K' "$(( $(date +%s) - start ))" "${state:-?}" "$last"
  if [[ "$state" == *Restarting* ]]; then
    echo
    bad "vLLM이 재시작을 반복합니다. 오류 줄:"
    gpu_compose logs vllm 2>&1 | grep -iE "error|no space|401|403|gated" | tail -5
    exit 1
  fi
  sleep 10
done
echo
ok "vLLM 준비 ($(( $(date +%s) - start ))초)"
gpu_compose up -d worker >/dev/null
for _ in $(seq 1 60); do
  if curl -sf -m 3 "http://127.0.0.1:$PORT/actuator/health" | grep -q UP; then break; fi
  sleep 3
done
if curl -sf -m 3 "http://127.0.0.1:$PORT/actuator/health" | grep -q UP; then ok "worker 기동 ($WORKER_ID, 포트 $PORT)"; else
  bad "worker가 뜨지 않았습니다: docker compose -f docker-compose.real-gpu.yml --env-file .env logs worker"; exit 1; fi

step 6/6 "상시 서버 연결과 합류 확인"
reach=1
for svc in front-gateway:8080 broker-primary:8081 broker-replica:8082 membership-1:8101 membership-2:8102 model-manager:8110; do
  name=${svc%%:*}; p=${svc##*:}
  if curl -sf -m 3 "http://$CONTROL_IP:$p/actuator/health" >/dev/null 2>&1; then ok "$name ($CONTROL_IP:$p)"; else bad "$name ($CONTROL_IP:$p)"; reach=0; fi
done
if [ "$reach" = 0 ]; then
  echo "  ${YELLOW}상시 서버에 닿지 않습니다.${OFF} 확인할 것:"
  echo "   - 상시 서버가 켜져 있고 scripts/cloud/status.sh가 정상인지"
  echo "   - 보안 그룹 llm-control-sg에 '사용자 지정 TCP 8080-8130, 소스 = llm-sg' 규칙이 있는지 (docs/cloud-setup.md 3-1)"
  echo "   - 위 IP가 상시 서버의 '프라이빗' IP인지 (공인 IP가 아님)"
fi
joined=0
for _ in $(seq 1 40); do
  if gpu_compose logs worker 2>/dev/null | grep -q "event=worker.ready"; then joined=1; break; fi
  sleep 3
done
if [ "$joined" = 1 ]; then ok "worker 합류 (적재·생성 시험 통과, 인출 시작)"; else
  bad "2분 안에 합류하지 못했습니다 (model-manager에 닿는지, vLLM 생성 시험이 통과하는지 확인)"
  echo "   docker compose -f docker-compose.real-gpu.yml --env-file .env logs --tail 30 worker"
fi

echo
echo "상시 서버에서 실행할 명령:"
echo "  scripts/cloud/workers.sh add $WORKER_ID ${GPU_IP:-<이 서버 프라이빗 IP>}"
echo "GPU 서버를 다 붙였으면 상시 서버에서 마지막에 한 번:"
echo "  scripts/cloud/workers.sh apply"
echo "${DIM}이 서버를 껐다 켜면 vLLM과 worker가 자동으로 다시 뜬다(worker는 vLLM 생성 시험을 통과한 뒤에 일을 받는다).${OFF}"
if [ "$reach" = 0 ] || [ "$joined" = 0 ]; then exit 1; fi
