# 클라우드 상시 서버 설정 (CPU 서버 1대)

나와 팀원이 언제든 브라우저로 접속할 수 있는 서버를 AWS에 만든다. 평소에는 **중지**해 두고, 쓸 때만 **시작**한다.

- 켜면 3~5분 안에 앱이 자동으로 다 뜬다. 창 3개, 로컬 Docker, SSH 터널이 필요 없다.
- GPU가 없을 때는 이 서버의 mock worker 2대로 흐름(순번 안내, 스트리밍, 장애 시연)을 확인한다.
- GPU 서버를 켜면 real worker로 바꿔 실제 답변을 받는다.
- 매일 00:00(한국 시간)에 자동으로 중지된다. 켜 둔 채 잊어도 그날 밤에 꺼진다.

| 상태 | 비용 (서울, 대략) |
|---|---|
| 중지 | 디스크 30GB, 하루 약 100원 |
| 켜 둠 (t3.medium) | 시간당 약 80원 (공인 IP 포함) |
| 켜 둠 (m7i.2xlarge, 측정 날만) | 시간당 약 700원 |

GPU 서버 비용은 별도다(g6.xlarge 시간당 약 1,400원). GPU 서버는 [README real 모드 실행](../README.md#real-모드-실행-gpu-2대--cpu-1대)을 따른다.

---

## 1. 서버 만들기 (처음 한 번, 약 15분)

### 1-1. 보안 그룹 `llm-control-sg`

EC2 → 보안 그룹 → **보안 그룹 생성**에서 이름을 `llm-control-sg`로 하고, 인바운드 규칙을 아래처럼 추가한다. 리전은 **서울**이다.

| 유형 | 포트 | 소스 | 용도 |
|---|---|---|---|
| SSH | 22 | 내 IP | 서버 관리 |
| 사용자 지정 TCP | 8140 | 팀원 IP마다 한 줄 (`x.x.x.x/32`) | 화면 (이용자, 답변 월, 운영자) |
| 사용자 지정 TCP | 8080 | 팀원 IP마다 한 줄 | 질문 접수 (화면이 직접 부름) |
| 사용자 지정 TCP | 8110 | 팀원 IP마다 한 줄 | 운영자 화면의 모델 교체 |
| 사용자 지정 TCP | 8120 | 팀원 IP마다 한 줄 | 운영자 화면의 지표·알림 |
| 사용자 지정 TCP | 8080-8130 | 보안 그룹 `llm-sg` (GPU 서버) | GPU 서버의 worker가 broker·membership·front-gateway를 부름 |

- **0.0.0.0/0(전체 공개)은 쓰지 않는다.** 앱에 로그인이 없고, 운영자 화면에는 모델 교체 버튼이 있다.
- 팀원 IP는 각자 네이버에 "내 IP"를 검색해서 알려 달라고 한다. 학교에서 쓸 거면 학교 IP도 한 줄 넣는다.
- GPU 서버의 보안 그룹 `llm-sg`에도 **사용자 지정 TCP 8091-8095, 소스 = `llm-control-sg`** 규칙을 추가한다. membership과 monitoring이 GPU 서버의 worker를 부르기 때문이다.

### 1-2. 인스턴스 시작

EC2 → 인스턴스 → **인스턴스 시작**에서 아래처럼 고른다.

- 이름: `llm-control`
- AMI: **Ubuntu Server 24.04 LTS** (빠른 시작 탭, 64비트 x86)
- 인스턴스 유형: **t3.medium**
- 키 페어: `llm-key`
- 네트워크 설정 → 편집 → 퍼블릭 IP 자동 할당 **활성화**, 기존 보안 그룹 **`llm-control-sg`**
- 스토리지: **30** GiB, **gp3**
- 고급 세부 정보 → **종료 방식**: **중지** (기본값 그대로. "종료(terminate)"로 바꾸지 않는다)

**인스턴스 시작**을 누르고, 상태가 `실행 중`이 되면 퍼블릭 IPv4 주소를 복사한다.

### 1-3. 접속하고 설치 (Windows PowerShell)

```powershell
ssh -i C:\aws\llm-key.pem ubuntu@<퍼블릭IP>
```

서버 안에서 아래를 실행한다.

```bash
git clone https://github.com/JangSwan/boonsan-2-LLM.git
cd boonsan-2-LLM
sudo scripts/cloud/setup.sh
```

- 레포가 비공개면 `git clone`할 때 GitHub 아이디와 토큰(비밀번호 대신)을 묻는다.
- 처음에는 jar와 이미지를 빌드하느라 **10~20분** 걸린다.
- 끝나면 접속 주소가 나온다.

```
이용자 화면:  http://<공인IP>:8140
답변 월:      http://<공인IP>:8140/wall.html
운영자 화면:  http://<공인IP>:8140/admin.html
```

1~2분 뒤 상태를 확인한다.

```bash
scripts/cloud/status.sh
```

모두 초록(✔)이고 "시험 질문 첫 토큰 …ms"가 나오면 끝이다. 브라우저로 위 주소를 열어 질문해 본다.

---

## 2. 평소에 쓰는 법

### 켜기

1. EC2 콘솔 → `llm-control` → **인스턴스 상태 → 인스턴스 시작**
2. 3~5분 기다린다. 앱은 서버가 켜질 때 자동으로 뜬다.
3. **공인 IP가 켤 때마다 바뀐다.** 콘솔의 퍼블릭 IPv4 주소로 `http://<공인IP>:8140`을 연다. 서버에 접속해 있다면 `scripts/cloud/info.sh`로도 볼 수 있다.

### 끄기

- 다 쓰면 콘솔에서 **인스턴스 중지**를 누른다.
- 잊어도 매일 00:00(한국 시간)에 자동으로 중지된다. 10분 전에 접속 중인 터미널에 알림이 뜬다.
- 밤늦게 측정해야 하면 그날만 `sudo rm /etc/cron.d/llm-auto-stop`으로 끄고, 끝나면 `sudo scripts/cloud/setup.sh`를 다시 실행해 되돌린다. 빌드는 캐시 덕분에 빨리 끝난다.

### 팀원 IP 추가

1. 팀원에게 네이버 "내 IP" 결과를 받는다.
2. EC2 → 보안 그룹 → `llm-control-sg` → **인바운드 규칙 편집**
3. 8140, 8080, 8110, 8120 규칙을 소스 `팀원IP/32`로 하나씩 추가하고 **규칙 저장**을 누른다. 설명 칸에 이름을 적어 두면 관리하기 쉽다.
4. 서버를 다시 켤 필요 없이 바로 적용된다.

집 IP는 가끔 바뀐다. 갑자기 접속이 안 되면(브라우저에서 시간 초과) IP부터 다시 확인한다.

### 코드 갱신 (main에 새 PR이 머지됐을 때)

```bash
cd ~/boonsan-2-LLM
git pull
scripts/real/build-jars.sh
docker compose -f docker-compose.yml -f docker-compose.cloud.yml --env-file .env build
scripts/cloud/up.sh
```

---

## 3. GPU 서버 붙이기 (real 모드)

### 3-1. GPU 서버 띄우기

GPU 서버마다 README [real 모드 실행](../README.md#real-모드-실행-gpu-2대--cpu-1대)의 2~3단계를 따른다. 다른 점은 두 가지다.

- GPU 서버 `.env`의 `CONTROL_IP`에는 이 상시 서버의 **프라이빗 IP**를 넣는다. `scripts/cloud/info.sh` 마지막 줄에 나온다. 프라이빗 IP는 중지·시작해도 바뀌지 않는다.
- AMI로 만든 GPU 서버에는 예전 `vllm` 컨테이너가 남아 있어 포트가 겹친다. 먼저 `docker rm -f vllm`을 실행한다.

```bash
# GPU 서버에서
docker rm -f vllm 2>/dev/null; docker compose -f docker-compose.real-gpu.yml --env-file .env up -d
```

GPU 서버에도 밤 자동 중지를 걸어 둔다. GPU는 하루 켜 두면 약 3.4만원이다.

```bash
echo '0 15 * * * root /sbin/shutdown -h now' | sudo tee /etc/cron.d/llm-auto-stop
```

### 3-2. 상시 서버를 real 모드로

상시 서버에서 `.env`의 `REAL_WORKER_URLS`와 `REAL_CONCURRENCY_CAP`을 채운다.

```bash
nano .env
# REAL_WORKER_URLS=worker-1=http://<GPU1 프라이빗IP>:8091,worker-2=http://<GPU2 프라이빗IP>:8092
# REAL_CONCURRENCY_CAP=100      # GPU 2대 × 슬롯 50. GPU 5대(4+1)면 200
scripts/cloud/mode.sh real
scripts/cloud/status.sh
```

- `mode.sh real`은 mock worker를 내리고, 상한을 바꾸고, 활성 단계를 FP8로 맞춘다.
- 바뀐 설정을 읽는 서비스(broker, membership, monitoring, model-manager)만 다시 만들어진다. 이때 대기열이 비워진다.
- GPU를 다 끄면 `scripts/cloud/mode.sh mock`으로 되돌린다. 같은 모드로 여러 번 실행해도 결과는 같다.

### 3-3. 측정 날: 서버 크기 키우기

QAS1 폭주(1,000건)는 SSE 연결 1,000개를 동시에 잡으므로 t3.medium으로는 부족하다. t3는 CPU를 오래 높게 쓰면 추가 요금도 붙는다.

1. 콘솔에서 `llm-control` **중지**
2. **작업 → 인스턴스 설정 → 인스턴스 유형 변경** → **m7i.2xlarge**
3. **시작** → 접속해서 메모리 상한을 키운다

```bash
scripts/cloud/size.sh measure
```

측정이 끝나면 반대로 한다: 중지 → 유형 **t3.medium** → 시작 → `scripts/cloud/size.sh light`

부하 생성기는 이 서버에서 돌린다 (README 5. QAS 측정).

```bash
docker run --rm --network host --ulimit nofile=65535:65535 -v "$PWD":/w -w /w eclipse-temurin:21-jdk \
  java scripts/loadtest/LoadTest.java --mode steady --concurrency 160 --duration-sec 120 --warmup-sec 20 --check --csv qas2.csv
```

---

## 4. 데모 당일 "20분 전 결정"

```bash
scripts/cloud/status.sh
```

| 결과 | 진행 |
|---|---|
| 모두 ✔ | 라이브 (Plan A) |
| 워커 수가 모자람 | 뜬 대수로 라이브 (Plan B) |
| 앱이나 시험 질문이 ✘ | 노트북 mock (Plan C) 또는 녹화 영상 (Plan D) |

이 시점 이후에는 고치지 않는다.

---

## 참고

### 메모리 (t3.medium 4GB에 들어가는 이유)

로컬에서 잰 앱별 메모리(RSS)다. 유휴 → 부하 중 최대 순이다.

| 앱 | MB |
|---|---|
| client | 136 |
| fake-aws | 147 |
| model-manager | 147 → 162 |
| monitoring | 155 → 161 |
| worker (mock) | 160 → 172 |
| broker-replica | 172 → 190 |
| membership | 190 → 208 |
| front-gateway | 153 → 297 |
| broker-primary | 197 → 288 |

가벼운 구성(앱 11개 + redis)은 부하 중에도 합계 약 2.2GB다. 컨테이너 상한은 `MEM_SMALL` 256MB, `MEM_MEDIUM` 320MB, `MEM_LARGE` 448MB이고, 그 60%를 힙으로 쓴다. `size.sh measure`는 512MB / 768MB / 2GB로 올린다.

### CORS와 보안

- 공인 IP가 켤 때마다 바뀌므로 `CORS_ORIGINS`에 `http://*:8140` 패턴을 쓴다. 8140 포트에서 열린 화면이면 어느 주소든 API를 부를 수 있다는 뜻이다.
- 실제로 누가 접근할 수 있는지는 **보안 그룹(팀원 IP)**이 막는다. 앱에는 로그인이 없으므로 보안 그룹을 전체 공개로 바꾸지 않는다.
- 고정 주소가 필요하면 탄력적 IP를 붙이고 `CORS_ORIGINS=http://<탄력적IP>:8140`으로 좁힌다. 탄력적 IP는 서버를 중지해 둔 동안에도 요금이 나간다.

### 스크립트

| 스크립트 | 하는 일 |
|---|---|
| `sudo scripts/cloud/setup.sh` | 처음 설정 (Docker, 스왑, `.env`, 빌드, 자동 기동, 밤 자동 중지) |
| `scripts/cloud/up.sh` | 앱 띄우기 (서버가 켜질 때 자동으로 실행됨) |
| `scripts/cloud/status.sh` | 한 화면 상태 확인 |
| `scripts/cloud/info.sh` | 접속 주소, 프라이빗 IP |
| `scripts/cloud/mode.sh mock\|real` | mock worker ↔ GPU real worker |
| `scripts/cloud/size.sh light\|measure` | 서버 크기에 맞춘 메모리 상한 |

### 로그 보기

```bash
docker compose -f docker-compose.yml -f docker-compose.cloud.yml --env-file .env logs -f front-gateway
docker compose -f docker-compose.yml -f docker-compose.cloud.yml --env-file .env ps
```
