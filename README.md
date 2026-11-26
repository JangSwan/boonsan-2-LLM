# LLM Serving Platform

자체 호스팅 오픈소스 LLM(8B) 서빙 플랫폼 (소프트웨어 아키텍처 수업 팀 프로젝트). 이용자 약 1,000명 규모 설계를 코드로 옮겼다.
규칙은 [`CLAUDE.md`](CLAUDE.md), 클래스·오퍼레이션·호출 흐름은 [`docs/design.md`](docs/design.md)(EA 모델)를 따른다.

| 전략 | 내용 | 구현 위치 |
|---|---|---|
| C2 | 공용 대기열 + 실행 크레딧 Pull | `broker` (AdmissionController, SharedQueue, LeaseManager), `worker` (CreditManager, LeaseRenewer) |
| T3 | 시간 기준 분할 (스텝 예산 B, Prefill 조각) | `worker` (EngineScheduler, BudgetPolicy/BudgetController, BudgetGuard) |
| U1 | 모델 축소 사다리, 품질 게이트, 롤백 | `model-manager` (ModelConfig, QualityGate, RollbackManager), `worker` (ModelRuntime) |
| R3 | N+1 활성 여분 (5대 운영, 4대분 상한) | `membership` (HealthDetector, LeaseReclaimer, Replenisher, LeaderLock), `broker` (LeaseLimiter) |

## 모듈

| 모듈 | 포트 | 역할 |
|---|---|---|
| `common` | – | 요청/응답 DTO, API 경로 상수 (로직 없음) |
| `front-gateway` | 8080 | 질문 접수(POST → SSE), 요청 ID·버전 태그, 토큰 중계, 중복 필터 |
| `broker` | 8081 / 8082 | 접수, 공용 큐, lease, 4대분 상한, 재시도·격리, 동기 복제와 쿼럼 승격 (주 / 복제본) |
| `worker` | 8091~8095 / 8096 | 크레딧 pull, 스텝 예산, 엔진 스케줄링, 가짜 엔진, lease 갱신, 하트비트 (5대 / 예비) |
| `membership` | 8101 / 8102 | 하트비트 감지, 회수 통지, lease 회수, 재보충, 복구 알림, 리더 락 |
| `model-manager` | 8110 | 모델 교체, 사다리 단계, 품질 게이트, 버전 비율, 롤백 |
| `monitoring` | 8120 | 지표 수집, 알림, 품질 샘플 전달 |
| `fake-aws` | 8130 | 스팟 회수 통지, 대체 인스턴스 기동 흉내 (mock) |
| `client` | 8140 | Web UI(`/`), AdminConsole(`/admin.html`), 외부 프로그램용 `ApiClient` |
| redis | 6379 | Membership 리더 락, `broker-primary` 락 (두 락에만 사용) |

## 실행

```bash
./gradlew build            # 빌드 + 단위·구조(ArchUnit) 테스트
docker compose up --build  # 전체 실행 (mock 프로필)
```

- 이용자 화면: http://localhost:8140 , 운영자 화면: http://localhost:8140/admin.html
- 명령줄 질문: `curl -N -X POST localhost:8080/api/front-gateway/questions -H 'Content-Type: application/json' -d '{"text":"안녕하세요"}'`
- Docker 없이 실행: `./gradlew build -x test && scripts/local-run.sh start` (같은 포트, `scripts/local-run.sh stop worker-5`로 장애 시연)

## 시나리오 (sd1 ~ sd6)

전체를 띄운 뒤 `scripts/scenarios/run-all.sh`(Docker 없이 띄웠으면 `MODE=local`)로 자동 확인한다. CI의 `scenarios` job이 docker compose 위에서 같은 스크립트를 돌린다.

| 시나리오 | 스크립트가 하는 일 | 확인하는 것 |
|---|---|---|
| sd1 순번 안내·크레딧 pull | 동시 질문 60건 | 모두 순번 안내 후 완료, 동시 실행 ≤ 32(4대분), 중복 순번 없음 |
| sd2 스텝 예산 | 긴 입력(약 4,000토큰) 5건 직후 짧은 질문 5건 | 짧은 질문이 긴 입력보다 먼저, 3초 안에 첫 토큰 |
| sd4 요청 유발 장애 격리 | `[[crash]]`가 든 질문(가짜 엔진 비정상 종료) + 정상 질문 | 만료 1회차 재투입 → 2회차 격리 → 그 1건만 실패, 정상 질문 완료 |
| sd6 모델 교체·롤백 | SMALL 교체(거부) → FP8 교체 → 품질 이상 주입 | 게이트 미통과 거부, 한 대씩 교체 후 확정, 교체·롤백 중 끊긴 답변 0건, U0 복귀 |
| sd3 스팟 회수 | fake-aws 회수 통지 → `worker-5` 정지 | 인출 중지, 리더만 제외·회수·재보충, T서비스 ≤ 300초, worker-spare 합류, 알림 |
| sd5 브로커 승격 | 대기열이 있는 상태로 `broker-primary` 정지 | 복제본 승격(epoch 2), 승격 전후 요청 모두 완료(잃은 요청 0) |

손으로 시연할 때:

```bash
curl -X POST localhost:8130/api/fake-aws/spot-reclaims -H 'Content-Type: application/json' -d '{"workerId":"worker-5"}'
docker compose stop worker-5          # 서버 소멸
docker compose stop broker-primary    # 주 브로커 장애
docker compose logs -f | grep -E 'event=(worker\.excluded|lease\.reclaimed|broker\.promoted|retry\.quarantined|model\.)'
```

로그 형식은 `[instanceId] [requestId] event=<이벤트명> key=value ... msg=<설명>`이라 요청 ID 하나로 앱 사이 흐름을 grep할 수 있다.

## real 모드

외부 의존 세 가지는 Port 하나에 mock·real 구현을 두고 Spring 프로필로만 고른다 (`SPRING_PROFILES_ACTIVE=real`).

| Port | mock | real |
|---|---|---|
| `InferenceEnginePort` (worker) | `MockInferenceEngine` | `VllmEngineAdapter` (OpenAI 호환 `/v1/chat/completions` 스트림, `VLLM_URL`) |
| `SpotInstancePort` (membership) | `MockSpotInstanceClient` → fake-aws | `SpotInstanceClient` (AWS SDK EC2, `AWS_LAUNCH_TEMPLATE_ID`) |
| `ModelStorePort` (model-manager) | `MockModelStoreDAO` | `ModelStoreDAO` (S3) |

자격 증명과 엔진 주소는 `.env`로만 받는다 (커밋하지 않음).

real 모드 전제: AWS가 만든 대체 인스턴스의 `INSTANCE_ID`는 그 **EC2 인스턴스 ID**여야 한다. `Replenisher`는 `SpotInstancePort`가 돌려준 인스턴스 ID와 하트비트의 `workerId`가 같을 때 합류를 확인하기 때문이다 (mock에서는 fake-aws가 `worker-spare`를 돌려준다). 시작 템플릿(user-data)에서 인스턴스 메타데이터로 이 값을 넣는다. 이 전제가 지켜지면 대체 서버는 늘 새 ID로 합류하므로 중복 방지 키 `replace-<workerId>`(EC2 clientToken)가 다른 장애와 겹치지 않는다.

## real 모드 실행 (GPU 2대 + CPU 1대)

QAS1·QAS2를 실제 GPU로 재는 구성이다. 리전은 서울(ap-northeast-2), 서버 3대는 같은 VPC·같은 보안 그룹에 둔다.

| 서버 | 인스턴스 | 띄우는 것 | 파일 |
|---|---|---|---|
| GPU 1, 2 | g6.xlarge (L4), Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 22.04), 디스크 150GB | vLLM + worker 1개 | `docker-compose.real-gpu.yml` |
| CPU | m7i.2xlarge 정도, Ubuntu + Docker | broker 2, membership 2, model-manager, monitoring, fake-aws, front-gateway, client, redis, 부하 생성기 | `docker-compose.yml` + `docker-compose.real-control.yml` |

worker는 GPU 서버 안에서 돈다. 서버가 통째로 사라지면 하트비트도 같이 끊겨야 Membership이 장애를 감지하기 때문이다 (sd3). CPU 서버의 앱은 mock 프로필 그대로다. real이 필요한 외부 의존은 추론 엔진뿐이고, 재보충은 fake-aws로 시연한다.

### 1. 보안 그룹

| 유형 | 포트 | 소스 | 용도 |
|---|---|---|---|
| SSH | 22 | 팀원 IP (`x.x.x.x/32`) | 접속 |
| 사용자 지정 TCP | 8080-8140 | **이 보안 그룹 자신** (`sg-...`) | 서버 사이 앱 호출 |

vLLM(8000)과 앱 포트는 인터넷에 열지 않는다. 화면은 SSH 터널로 본다.

### 2. 코드와 jar (모든 서버)

```bash
git clone https://github.com/JangSwan/boonsan-2-LLM.git && cd boonsan-2-LLM
cp .env.example .env && nano .env      # 서버마다 값 채우기
scripts/real/build-jars.sh worker      # GPU 서버: worker만
scripts/real/build-jars.sh             # CPU 서버: 전체
```

서버에 Java를 설치하지 않는다. jar는 Docker 안에서 빌드한다. 레포가 비공개면 clone할 때 GitHub 토큰이 필요하다. `docker compose`가 없다고 나오면 `sudo apt-get install -y docker-compose-plugin`으로 설치한다.

### 3. 띄우기

CPU 서버를 먼저 띄운다.

```bash
# CPU 서버
docker compose -f docker-compose.yml -f docker-compose.real-control.yml --env-file .env up -d --build \
  redis broker-replica broker-primary membership-1 membership-2 model-manager monitoring fake-aws front-gateway client

# GPU 서버 (각각, .env의 INSTANCE_ID·SERVER_PORT만 다르게)
docker compose -f docker-compose.real-gpu.yml --env-file .env up -d --build
docker compose -f docker-compose.real-gpu.yml logs -f worker    # event=worker.joined 확인
```

`up`은 vLLM이 준비될 때까지 기다렸다가 worker를 띄운다. 처음에는 가중치를 받느라 10~15분 걸린다. 화면은 내 PC에서 CPU 서버로 터널을 열고 `http://localhost:8140`을 연다.

```bash
ssh -i llm-key.pem -N -L 8080:localhost:8080 -L 8110:localhost:8110 -L 8120:localhost:8120 -L 8140:localhost:8140 ubuntu@<CPU 퍼블릭 IP>
```

### 4. 슬롯과 상한 정하기 (1주차 측정)

GPU 서버에서 vLLM 로그의 두 줄을 본다.

```bash
docker compose -f docker-compose.real-gpu.yml logs vllm | grep -E "KV cache|Maximum concurrency"
```

- `Maximum concurrency for 8192 tokens per request: X`는 8,192토큰을 꽉 채운 요청 기준이다. QAS 부하는 짧은 질문 80%(500토큰 이하), 긴 입력 20%(약 4,000토큰)라 실제로는 더 들어간다. `GPU KV cache size: N tokens`를 요청당 평균 약 1,200토큰(보고서 4.4)으로 나눈 값을 상한으로 보고, 그보다 조금 낮게 `WORKER_MAX_SLOTS`를 정한다.
- `WORKER_KV_GB_PER_REQUEST` = 단계의 `kv-gb-per-gpu`(model-manager 설정, U0 6.0 / FP8 10.0) ÷ `WORKER_MAX_SLOTS`. 슬롯보다 KV 기준이 먼저 막히지 않게 맞춘다.
- CPU 서버의 `BROKER_CONCURRENCY_CAP`: QAS 측정은 GPU 대수 × 슬롯, N+1 장애 시연은 (GPU 대수 - 1) × 슬롯.
- 값을 바꾸면 `docker compose ... up -d`로 다시 띄운다. 코드는 고치지 않는다.

BF16(U0)·가중치 FP8·FP8 + KV FP8은 `.env`의 `VLLM_EXTRA_ARGS`만 바꿔 잰다 (`""` / `--quantization fp8` / `--quantization fp8 --kv-cache-dtype fp8`).

### 5. QAS 측정 (CPU 서버에서)

보고서의 수치는 GPU 4대분(상한 약 200)이다. GPU 2대면 C2 구조상 처리량이 대수에 비례하므로 절반으로 환산해 잰다.

| | 보고서 (4대분) | GPU 2대 |
|---|---|---|
| QAS2 동시 부하 (상한의 80%) | 160건 | 상한 × 0.8 (예: 상한 100이면 80건) |
| QAS1 폭주 | 1,000건 | 500건 |

```bash
# QAS2: 5분 측정 (앞 1분은 워밍업으로 뺀다)
docker run --rm --network host --ulimit nofile=65535:65535 -v "$PWD":/w -w /w eclipse-temurin:21-jdk \
  java scripts/loadtest/LoadTest.java --mode steady --concurrency 80 --duration-sec 300 --warmup-sec 60 --csv qas2.csv
# QAS1: 500건 폭주
docker run --rm --network host --ulimit nofile=65535:65535 -v "$PWD":/w -w /w eclipse-temurin:21-jdk \
  java scripts/loadtest/LoadTest.java --mode burst --requests 500 --csv qas1.csv
```

판정 기준은 보고서 그대로다: QAS2는 첫 토큰 p95 3초 이하, QAS1은 짧은 질문 30초·긴 입력 순번 안내 1초·첫 토큰 180초를 넘긴 요청과 실패를 합해 0.1% 이하. QAS1 결과에는 "30초 안에 첫 토큰을 받은 짧은 질문의 최대 초기 순번"도 나오므로, 기준을 못 넘으면 한계를 이 숫자로 보고한다.

**QAS 측정에는 mock 워커를 섞지 않는다.** 가짜 엔진이 처리량을 부풀린다. mock 워커를 섞는 것은 N+1 장애 시연 때만 한다.

### 6. 끝나면

- GPU 서버: `docker compose -f docker-compose.real-gpu.yml down` 후 콘솔에서 **중지** (오래 쉬면 AMI를 만들고 **종료**).
- CPU 서버도 중지한다. 켜 둔 GPU 1대가 하루 약 3.4만원이다.

## 설계에서 정하지 않아 구현에서 정한 것

`docs/design.md` 5절에 더해 구현 중 정한 것. 팀 검토가 필요하면 여기를 본다.

- **Prefill 조각 채우는 순서**: `EngineScheduler`는 스텝 예산 B 안에서 Decode 전부를 넣은 뒤, Prefill 조각을 **남은 입력이 적은 요청부터** 채운다. EA는 B 안의 순서를 정하지 않았고, sd2의 "짧은 요청 S가 다음 스텝에 통째로 끼어든다"를 만들려면 이 순서가 필요하다. 엔진(가짜 엔진, vLLM)이 알아서 고르는 것이 아니라 스케줄러가 정하므로 T3 전략 그대로다. 긴 입력 L은 매 스텝 남은 예산만큼 계속 읽힌다.
- **재시도 횟수 사본**: `RetryCounter`는 늘린 재시도 횟수를 작업 사본에 담아 재투입·격리하고, lease가 쥔 원본은 커밋이 성공할 때만 대체된다. 고립으로 커밋에 실패해도 같은 만료를 두 번 세지 않는다.
- **복제 묶음(BATCH)**: 인출(큐 제거 + lease 발급), 만료(lease 제거 + 재투입·격리), 회수(lease 제거 + 재투입)는 복제 메시지 하나로 커밋한다. 둘로 나누면 중간에 주 브로커가 죽을 때 요청이 사라진다 (sd5에서 실제로 재현해 고침).
- **재시도 작업 분산**: `SharedQueue.pullMatching`은 재시도 중인 작업을 pull 한 번에 1건까지만 내준다. 엔진을 죽인 요청과 같이 만료된 정상 요청이 다음 워커에 같이 실려 함께 격리되는 것을 막는다 (sd4 "그 요청 1건만 실패").
- **엔진 재시작 중 여유 0**: 엔진이 비정상 종료하면 생성 시험이 다시 통과할 때까지 `EngineScheduler.freeCapacity()`가 여유 0을 알린다.
- **승격 조건**: 복제본은 주 브로커의 메시지를 한 번이라도 받은 뒤에만 "연락 끊김"으로 승격을 검토한다 (동시 기동 때 잘못 승격하지 않게). 주 브로커는 변경이 없을 때도 생존 알림(PING)을 보낸다.
- **승격 직후 lease**: 승격하는 동안 갱신하지 못한 lease는 만료 시각을 TTL만큼 다시 준다 (기존 lease 유지).
- **교체 롤링**: `ModelConfig.onDrainStart`가 한 번에 한 대만 허가하고, 현재 버전을 인출하는 마지막 워커에는 `lastOfVersion`을 알려 큐에 남은 옛 버전 작업을 마저 가져간 뒤 멈추게 한다. 멈춤 판단은 빈 pull이 아니라 pull 응답의 `remainingForVersion`(큐에 남은 같은 버전 작업 수)으로 한다: 남은 작업과 진행 중 답변이 모두 0인 상태가 `last-drain-quiet-ms` 동안 이어질 때만 멈춘다 (피크에는 크레딧 0이나 4대분 상한 때문에 빈 pull이 이어져도 옛 버전 작업이 남아 있을 수 있다). 버전 태그는 `v1, v2, …`로 교체마다 새로 붙인다.
- **롤백**: 교체 도중이면 목표를 취소하고 활성 버전으로 되돌리며, 교체 완료 후면 U0를 새 버전으로 같은 롤링 절차를 쓴다.
- **품질 샘플 입력**: mock 모드에는 실제 응답 평가기가 없으므로 `POST /api/monitoring/quality-samples`로 샘플을 넣는다 (운영자 화면의 "품질 이상 주입").
- **접수 재시도**: Front Gateway는 broker가 느리거나 승격 중이면 오류 대신 같은 요청 ID로 최대 20초 동안 다시 보낸다 (broker가 같은 요청 ID를 한 번만 적재).

## 알려진 한계

시연(mock) 범위에서는 문제가 되지 않아 남겨 둔 것.

- **기다리는 요청의 진척**: `LeaseRenewer`는 스텝에 실제로 들어간 요청만 갱신한다 (진척이 끊기면 갱신을 멈춘다는 sd4 규칙). 짧은 요청이 몰리면 긴 입력이 한두 스텝 조각을 못 받을 수 있지만, 워커당 슬롯이 8이고 먼저 들어간 요청은 Prefill 뒤 Decode(스텝당 1토큰)로 넘어가므로, B 하한 256에서 밀림은 수백 ms 수준이라 진척 제한(4초)·lease TTL(10초)에 닿지 않는다. "엔진이 정상이면 워커가 든 요청 전부 갱신"으로 바꾸면 엔진이 죽지 않고 멈췄을 때 lease가 영원히 갱신되어 sd4 보호가 깨지므로 하지 않았다.
- **리더 교체 중 재보충 기록**: `Replenisher`의 대기 목록(대체 인스턴스 → 장애 워커)은 리더 메모리에만 있다. 재보충 도중 리더가 바뀌면 새 워커의 합류는 확인되지만 그 장애의 T재보충 기록과 알림은 빠진다. Membership은 데이터 복제 없이 하트비트로 다시 채우는 설계라 복제하지 않았다.
- **409 LEASE_NOT_OWNED**: lease ID는 발급마다 새 UUID라 한 lease는 한 워커의 것이고, 회수·만료된 lease는 지워져 404가 난다. 남의 lease를 갱신·완료하는 상황이 구조상 생기지 않으므로 renew·complete API에 workerId를 더하지 않았다.
