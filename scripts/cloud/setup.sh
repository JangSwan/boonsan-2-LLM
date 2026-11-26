#!/usr/bin/env bash
# 클라우드 상시 서버(Ubuntu 24.04) 처음 설정. 레포를 받은 뒤 한 번만 실행한다. 다시 실행해도 된다.
#   git clone https://github.com/JangSwan/boonsan-2-LLM.git && cd boonsan-2-LLM
#   sudo scripts/cloud/setup.sh
# 하는 일: Docker 설치·부팅 시 자동 시작, 스왑 2GB, .env 만들기, jar·이미지 빌드(서버에 Java 설치 없음),
#          부팅 때 앱을 띄우는 systemd 서비스(llm-control), 밤 00:00(한국 시간) 자동 중지, 앱 기동
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then exec sudo "$0" "$@"; fi
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OWNER=$(stat -c %U "$ROOT")
cd "$ROOT"

echo "[1/6] Docker 설치"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  apt-get update -q
  apt-get install -y -q docker.io docker-compose-v2 curl jq git
fi
command -v jq >/dev/null || apt-get install -y -q jq
systemctl enable --now docker
usermod -aG docker "$OWNER" || true

echo "[2/6] 스왑 2GB (4GB 서버에서 빌드할 때 여유)"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap -q /swapfile && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab
fi

echo "[3/6] .env"
if [ ! -f .env ]; then
  cp .env.cloud.example .env
  chown "$OWNER" .env
  echo "  .env를 만들었습니다 (mock 모드)"
else
  echo "  기존 .env를 그대로 씁니다"
fi

echo "[4/6] jar와 이미지 빌드 (처음에는 10~20분)"
scripts/real/build-jars.sh
docker compose -f docker-compose.yml -f docker-compose.cloud.yml --env-file .env build

echo "[5/6] 부팅 때 앱 자동 기동, 밤 자동 중지"
cat >/etc/systemd/system/llm-control.service <<UNIT
[Unit]
Description=LLM Serving Platform 상시 서버 (docker compose)
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$ROOT
ExecStart=$ROOT/scripts/cloud/up.sh
TimeoutStartSec=600

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable llm-control.service

# EC2 기본 "종료 시 동작"은 중지(stop)이므로 OS를 끄면 인스턴스가 중지된다 (GPU 요금·서버 요금이 멈춘다)
if [ "$(grep -E '^AUTO_STOP=' .env | cut -d= -f2)" != 0 ]; then
  cat >/etc/cron.d/llm-auto-stop <<'CRON'
# 매일 00:00 한국 시간(= 15:00 UTC)에 서버를 끈다. 끄기 10분 전에 접속자에게 알린다
50 14 * * * root wall "10분 뒤(00:00 KST) 서버가 자동으로 중지됩니다"
0 15 * * * root /sbin/shutdown -h now
CRON
  echo "  자동 중지: 매일 00:00 KST (/etc/cron.d/llm-auto-stop)"
else
  rm -f /etc/cron.d/llm-auto-stop
  echo "  자동 중지: 꺼짐 (AUTO_STOP=0)"
fi

echo "[6/6] 앱 기동"
systemctl restart llm-control.service
echo "  앱이 모두 뜨는 데 1~2분 걸립니다. 확인: scripts/cloud/status.sh"
scripts/cloud/info.sh || true
echo "docker 그룹은 다시 접속한 뒤부터 적용됩니다 (그 전에는 스크립트가 sudo로 부릅니다)."
