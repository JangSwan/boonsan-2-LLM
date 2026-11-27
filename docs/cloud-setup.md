# 클라우드 상시 서버 설정 (CPU 서버 1대)

나와 팀원이 언제든 브라우저로 접속할 수 있는 서버를 AWS에 만든다. 평소에는 **중지**해 두고, 쓸 때만 **시작**한다.

- 켜면 3~5분 안에 앱이 자동으로 다 뜬다. 창 3개, 로컬 Docker, SSH 터널이 필요 없다.
- GPU가 없을 때는 이 서버의 mock worker 2대로 흐름(순번 안내, 스트리밍, 장애 시연)을 확인한다.
- GPU 서버를 켜면 real worker로 바꿔 실제 답변을 받는다.
- 자동 중지는 기본으로 꺼져 있다. 끝날 때마다 직접 중지한다(3-8 체크리스트).

| 상태 | 비용 (서울, 대략) |
|---|---|
| 중지 | 디스크 30GB, 하루 약 100원 |
| 켜 둠 (t3.medium) | 시간당 약 80원 (공인 IP 포함) |
| 켜 둠 (m7i.2xlarge, 측정 날만) | 시간당 약 700원 |

GPU 서버 비용은 별도다(g6.xlarge 시간당 약 1,400원). GPU 서버 붙이기는 [3절](#3-gpu-붙이기-1대--2대--5대)을 따른다.

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
- **자동 중지는 기본으로 꺼져 있다**(밤늦게 작업하는 일이 많아서). 끝날 때마다 콘솔에서 `실행 중`이 0개인지 직접 확인한다.
- 매일 00:00(한국 시간) 자동 중지를 쓰고 싶으면 `.env`에서 `AUTO_STOP=1`로 바꾸고 `sudo scripts/cloud/setup.sh`를 다시 실행한다. 빌드는 캐시 덕분에 빨리 끝난다.

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

## 3. GPU 붙이기 (1대 / 2대 / 5대)

GPU 서버(g6.xlarge)를 AMI `llm-gpu-vllm-fp8`로 띄워 이 상시 서버에 붙인다. **GPU 서버마다 한 줄, 상시 서버에서 한 줄**이면 된다.

| GPU 대수 | 상한 (`workers.sh apply`가 정함) | QAS2 동시 부하 | QAS1 폭주 | 설명 |
|---|---|---|---|---|
| 1대 | 50 | 40건 | 250건 | 연습 |
| 2대 | 100 | 80건 | 500건 | 보고서(4대분)를 절반으로 환산 |
| 5대 | 200 (4대분) | 160건 | 1,000건 | 보고서 그대로. R3: 5대 운영, 4대분 상한 |

GPU 1대는 켜 둔 시간당 약 1,400원이다. 5대면 시간당 약 7,000원이다. 할당량(G and VT 인스턴스)은 대수 × 4 vCPU가 필요하다.

### 3-1. 보안 그룹: GPU 서버 ↔ 상시 서버 (처음 한 번)

두 보안 그룹이 서로를 소스로 허용한다. 설명 칸에는 **영어만** 쓴다(한글은 AWS가 거부한다).

| 보안 그룹 | 유형 | 포트 범위 | 소스 | 설명 | 이유 |
|---|---|---|---|---|---|
| `llm-control-sg` (상시 서버) | 사용자 지정 TCP | 8080-8130 | `llm-sg` | `from gpu workers` | worker가 front-gateway(8080, 토큰 전달), broker(8081·8082, pull·갱신·완료), membership(8101·8102, 하트비트), model-manager(8110, 설정·가중치)를 부른다 |
| `llm-sg` (GPU 서버) | 사용자 지정 TCP | 8091-8095 | `llm-control-sg` | `from control` | membership(인출 중지)과 monitoring(지표)이 worker를 부른다 |

넣는 법: EC2 → **보안 그룹** → 그룹 선택 → 아래 **인바운드 규칙** 탭 → **인바운드 규칙 편집** → **규칙 추가** → 유형 `사용자 지정 TCP`, 포트 범위 입력 → 소스 `사용자 지정`을 두고 검색 칸에 `llm-`을 치면 나오는 보안 그룹(sg-…)을 고른다 → 설명 입력 → **규칙 저장**. 1-1에서 `llm-control-sg`의 8080-8130 규칙을 이미 넣었다면 `llm-sg` 쪽만 넣는다.

### 3-2. GPU 서버 띄우기 (쓰기 30~60분 전에)

AMI로 처음 띄운 서버는 디스크를 처음 읽을 때 느려서 vLLM 준비에 10~30분이 걸린다. 그래서 일찍 띄운다.

1. EC2 → **AMI** → `llm-gpu-vllm-fp8` 선택 → **AMI로 인스턴스 시작**
2. 이름 `llm-gpu`, 인스턴스 유형 **g6.xlarge**, 키 페어 `llm-key`
3. 네트워크 설정 → **편집** → 서브넷은 **상시 서버와 같은 가용 영역**(상시 서버 세부 정보의 가용 영역), 퍼블릭 IP 자동 할당 **활성화**, 기존 보안 그룹 **`llm-sg`**
4. 스토리지는 그대로(150GiB). 오른쪽 요약의 **인스턴스 개수**에 대수(1, 2, 5)를 넣고 **인스턴스 시작**
5. 목록에서 이름 칸의 연필을 눌러 `llm-gpu-1` ~ `llm-gpu-5`로 바꿔 둔다. 이 번호가 워커 ID다.

- 중지해 둔 `llm-gpu-1`이 있으면 그 서버를 **시작**해서 worker-1로 쓰고, 나머지만 AMI로 띄운다.
- `InsufficientInstanceCapacity`(그 영역에 GPU 재고 없음)가 나오면 서브넷을 다른 영역(`2a`, `2c`, `2d` 중 하나. `2b`에는 g6가 없다)으로 바꿔 모자란 대수만 다시 띄운다. 영역이 달라도 동작한다.
- `VcpuLimitExceeded`는 할당량 부족이다. 승인된 대수로 진행한다.

### 3-3. GPU 서버마다 합류 (서버당 한 줄)

상시 서버의 **프라이빗 IP**를 먼저 확인한다(상시 서버에서 `scripts/cloud/info.sh` 마지막 줄, 또는 콘솔 세부 정보의 프라이빗 IPv4 주소). GPU 서버마다 PowerShell 창을 하나씩 열어 동시에 진행하면 빠르다.

```powershell
ssh -i C:\aws\llm-key.pem ubuntu@<GPU 서버 공인IP>
```

```bash
# 처음 (레포가 없을 때): N은 그 서버의 번호(llm-gpu-N), IP는 상시 서버의 프라이빗 IP
git clone https://github.com/JangSwan/boonsan-2-LLM.git && cd boonsan-2-LLM && scripts/cloud/gpu-join.sh worker-N <상시 서버 프라이빗 IP>
# 다시 실행할 때 (코드 갱신 등)
cd boonsan-2-LLM && git pull && scripts/cloud/gpu-join.sh worker-N <상시 서버 프라이빗 IP>
```

`gpu-join.sh`가 하는 일:
1. AMI에 남은 예전 `vllm` 컨테이너에서 Hugging Face 토큰을 꺼내 `.env`에 넣고(화면에 출력하지 않는다), 예전 컨테이너를 지운다. 토큰을 못 찾으면 입력을 받는다.
2. `.env`에 워커 ID·포트(worker-N → 809N), 상시 서버 IP, 실측으로 정한 값(FP8 + KV FP8, 슬롯 50, KV 0.2, B 2048)을 쓴다.
3. worker 이미지를 빌드하고(처음 5~10분), vLLM을 띄워 준비될 때까지 진행 상황을 보여 준 뒤 worker를 띄운다.
4. 상시 서버의 앱 6개에 닿는지, worker가 적재·생성 시험을 통과했는지 ✔/✘로 보여 주고, **상시 서버에서 칠 줄**(`scripts/cloud/workers.sh add worker-N <이 서버 IP>`)을 출력한다.

여러 번 실행해도 결과가 같다. ✘가 나오면 안내대로 보안 그룹(3-1)과 상시 서버 상태를 확인하고 다시 실행한다. GPU 서버를 껐다 켜면 vLLM과 worker가 자동으로 다시 뜬다. worker는 vLLM 생성 시험을 통과하기 전에는 일을 받지 않는다.

### 3-4. 상시 서버에서 반영 (한 번)

각 GPU 서버가 출력한 줄을 붙여 넣고, 마지막에 `apply`를 한 번 실행한다.

```bash
scripts/cloud/workers.sh add worker-1 172.31.x.x
scripts/cloud/workers.sh add worker-2 172.31.y.y
# ... 대수만큼
scripts/cloud/workers.sh apply        # real 모드로 바꾸고 상한을 정함 (5대면 200, 아니면 대수 × 50)
scripts/cloud/status.sh               # 워커 N대 응답, 시험 질문 첫 토큰 확인
```

- `apply`는 대수에 맞춘 QAS2·QAS1 부하 크기와 부하 생성기 명령을 출력한다.
- `scripts/cloud/workers.sh list`는 목록과 각 워커 응답 여부를 보여 준다. 뺄 때는 `remove worker-N` 후 다시 `apply`.
- 반영할 때 broker가 다시 만들어지며 대기열이 비워진다. 측정 전에 한다.

### 3-5. 측정 날: 상시 서버 크기 키우기

QAS1 폭주(1,000건)는 SSE 연결 1,000개를 동시에 잡으므로 t3.medium으로는 부족하다. t3는 CPU를 오래 높게 쓰면 추가 요금도 붙는다.

1. 콘솔에서 `llm-control` **중지**
2. **작업 → 인스턴스 설정 → 인스턴스 유형 변경** → **m7i.2xlarge**
3. **시작** → 접속해서 메모리 상한을 키운다

```bash
scripts/cloud/size.sh measure
```

측정이 끝나면 반대로 한다: 중지 → 유형 **t3.medium** → 시작 → `scripts/cloud/size.sh light`

부하 생성기는 이 서버에서 돌린다. `workers.sh apply`가 GPU 대수에 맞춘 명령을 출력하므로 그대로 복사해 쓴다. 5대일 때는 아래와 같다.

```bash
# QAS2: 상한의 80% (5대 → 160건), 2분
docker run --rm --network host --ulimit nofile=65535:65535 -v "$PWD":/w -w /w eclipse-temurin:21-jdk \
  java scripts/loadtest/LoadTest.java --mode steady --concurrency 160 --duration-sec 120 --warmup-sec 20 --check --show-answers --csv qas2.csv
# QAS1: 폭주 1,000건
docker run --rm --network host --ulimit nofile=65535:65535 -v "$PWD":/w -w /w eclipse-temurin:21-jdk \
  java scripts/loadtest/LoadTest.java --mode burst --requests 1000 --check --answers qas1-answers.jsonl --csv qas1.csv
```

- 결과 요약 끝의 **판정(PASS/FAIL)**과 **"섞임 0건"**을 캡처한다. `qas2.csv`, `qas1.csv`, `qas1-answers.jsonl`은 발표 자료용으로 내 PC에 받아 둔다: `scp -i C:\aws\llm-key.pem ubuntu@<공인IP>:boonsan-2-LLM/qas*.csv .`

### 3-6. N+1 시연 (R3)

- **5대:** `apply`(상한 200)로 둔다. QAS2 부하를 거는 중에 콘솔에서 GPU 서버 1대를 **인스턴스 중지**한다. 운영자 화면에 `worker.failure`(하트비트 끊김, 제외, lease 회수)가 뜨고, 남은 4대가 상한 200을 그대로 채운다(4 × 50).
- **2대로 보여 줄 때:** 상한을 1대분으로 낮춘다: `scripts/cloud/workers.sh apply --cap 50`. 1대를 중지해도 남은 1대가 50을 채운다. 측정으로 돌아갈 때는 `apply`(상한 100).
- 재보충(대체 서버 합류)은 이 구성에 예비 워커가 없어 일어나지 않는다. mock(`mode.sh mock`)이나 로컬 `docker compose`의 sd3 시연으로 보여 준다.
- 중지한 GPU 서버는 다시 **시작**하면 자동으로 합류한다.

### 3-7. 녹화 (Plan D 대비)

- Windows: 녹화할 창을 앞에 두고 **Win + Alt + R** (Xbox Game Bar, 창 하나). 여러 창을 한 화면에 담으려면 OBS Studio의 "디스플레이 캡처"를 쓴다.
- 답변 월, 운영자 화면, 부하 생성기 터미널(마지막 판정까지)을 한 번에 담는다.

### 3-8. 끝낼 때 체크리스트

1. 상시 서버: `scripts/cloud/mode.sh mock` (다음에 GPU 없이 켜도 바로 쓰도록). 크기를 키웠다면 중지 → 유형 **t3.medium** → 시작 → `scripts/cloud/size.sh light`
2. GPU 서버: 그날 AMI로 띄운 것은 **인스턴스 종료**(디스크도 함께 지워짐). 계속 쓸 `llm-gpu-1`만 **중지**
3. EC2 → 인스턴스 목록에서 **실행 중 0개** 확인
4. EC2 → **볼륨**에서 상태가 `사용 가능`(어디에도 안 붙은 디스크)인 것이 없는지 확인. 있으면 삭제

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
| `scripts/cloud/workers.sh add\|remove\|list\|apply` | GPU 워커 목록 관리, real 모드 반영과 상한 계산 (상시 서버) |
| `scripts/cloud/gpu-join.sh worker-N <IP>` | GPU 서버 한 대를 합류 (GPU 서버에서) |
| `scripts/cloud/size.sh light\|measure` | 서버 크기에 맞춘 메모리 상한 |

### 로그 보기

```bash
docker compose -f docker-compose.yml -f docker-compose.cloud.yml --env-file .env logs -f front-gateway
docker compose -f docker-compose.yml -f docker-compose.cloud.yml --env-file .env ps
```


## 데모에서 부하와 답변을 함께 보여 주기

화면 세 개를 함께 띄운다.

| 화면 | 보여 주는 것 |
|---|---|
| 답변 월 `http://<공인IP>:8140/wall.html?start&tiles=24` | 서로 다른 질문의 답이 타일마다 동시에 흐른다 |
| 운영자 화면 `http://<공인IP>:8140/admin.html` | 부하 생성기까지 합친 실행 중 수, 워커별 세션, 대기열 |
| 부하 생성기 터미널 (`--check --show-answers`) | 수십~수백 건의 답이 한 줄씩 지나가고, 끝에 PASS와 "섞임 0건" |

- **답변 월 타일 수:** 브라우저는 같은 주소로 동시에 6개까지만 연결한다. 그래서 같은 서버의 다른 이름으로 나눠 보낸다. 쓰는 이름은 공인 IP, EC2 공개 DNS(`ec2-a-b-c-d.ap-northeast-2.compute.amazonaws.com`), `a.b.c.d.nip.io`, `w2.a.b.c.d.nip.io`다. 시작할 때 닿는 이름만 골라 이름 수 × 6개(최대 24개)를 띄운다. 회의장 네트워크가 nip.io를 막으면 12개로 줄어든다. 주소를 직접 주려면 `&hosts=이름1,이름2`를 붙인다.
- **부하 생성기 (CPU 서버에서):**

```bash
docker run --rm --network host -v "$PWD":/w -w /w eclipse-temurin:21-jdk \
  java scripts/loadtest/LoadTest.java --mode steady --concurrency 160 --duration-sec 120 --warmup-sec 20 --check --show-answers
```
