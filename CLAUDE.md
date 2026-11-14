# CLAUDE.md — LLM Serving Platform

소프트웨어 아키텍처 수업 팀 프로젝트. 자체 호스팅 오픈소스 LLM(8B) 서빙 플랫폼을 이용자 약 1,000명 규모로 설계했고, 그 설계를 코드로 구현한다.
이 파일은 모든 작업에 적용되는 규칙이다. 클래스·오퍼레이션·호출 흐름의 상세는 `docs/design.md`를 따른다.

## 1. 설계 기준 (가장 우선)

- 설계 원본은 EA 모델의 런타임 뷰(Structural, Queue Broker / Worker x (4+1) / Membership 차일드), 시퀀스 sd1~sd6, Module View다. `docs/design.md`는 이 모델을 옮긴 문서다.
- 런타임 뷰에 없는 컴포넌트 간 호출을 새로 만들지 않는다.
- 클래스 이름, 오퍼레이션 이름, 패키지 구조는 `docs/design.md`와 같게 쓴다. 임의로 이름을 바꾸거나 클래스를 합치거나 쪼개지 않는다.
- 설계에 없는 클래스가 필요하면 기술적 보조 클래스(설정, DTO 매핑, 예외 등)만 추가한다. 업무 규칙을 가진 클래스가 새로 필요해 보이면 만들지 말고 먼저 사람에게 묻는다.
- 설계와 요청이 충돌하면 코드를 쓰기 전에 충돌 내용을 알린다.

### 선택한 전략과 구현 위치

| 전략 | 내용 | 구현 위치 |
|---|---|---|
| C2 | 공용 대기열 + 실행 크레딧 Pull | Queue Broker, Worker(Credit Manager, Lease Renewer) |
| T3 | 시간 기준 분할 (스텝 예산 B, Prefill 조각 실행) | Worker(Engine Scheduler, Budget Controller, Budget Guard) |
| U1 | L4 4대 + 모델 축소 사다리 | Model Manager, Worker(Model Runtime) |
| R3 | N+1 활성 여분 (5대 운영, 4대분 상한) | Membership, Queue Broker(Lease Limiter) |

## 2. 실행 구조

- 컴포넌트마다 별도 Spring Boot 앱이고, 한 레포의 Gradle 멀티모듈로 관리한다.
- 같은 컴포넌트의 여러 대는 같은 앱을 여러 인스턴스로 띄운다.
  - Worker 5대 (worker-1 ~ worker-5) + 재보충용 예비 1대 (worker-spare, 기동 신호 전까지 대기)
  - worker-spare는 R3의 "+1 여분"이 아니다. R3의 여분은 worker-1 ~ worker-5 중 하나로 평소에도 활성 상태로 일한다. worker-spare는 AWS가 새로 만들어 주는 대체 인스턴스를 흉내 내는 것이므로, 기동 신호를 받기 전에는 하트비트도 pull도 하지 않는다.
  - Membership 2대 (리더 락을 가진 1대만 조치)
  - Queue Broker 주 1대 + 복제본 1대
- 인스턴스 구분은 환경변수로 한다: `INSTANCE_ID`, `SERVER_PORT`, `BROKER_ROLE`(primary / replica). `BROKER_ROLE`은 시작할 때의 역할이고, 승격 후의 역할은 broker 앱이 메모리에서 관리한다.
- 전체 실행은 `docker compose up`, 갑작스러운 장애 시연은 `docker compose stop <서비스명>`으로 한다.
- Membership 리더 락과 Broker 승격용 `broker-primary` 락의 저장소로 Redis를 쓴다.

### 상태 저장 위치
- DB는 쓰지 않는다. 상태는 각 앱의 메모리에 둔다.
- Queue Broker의 공용 큐, lease, 격리 큐는 broker 앱 메모리에 두고, 모든 변경은 Replicator가 복제본 broker에 동기 복제한 뒤 커밋한다. 큐나 lease를 Redis·DB로 옮기지 않는다(C2 보완 복제 설계와 달라진다).
- Membership의 MemberRegistry는 메모리에 두고 하트비트로 다시 채운다(데이터 복제 없음).
- Redis는 락 두 개에만 쓴다: Membership 리더 락, Queue Broker의 `broker-primary` 락(쿼럼의 세 번째 표, 6절 sd5). 큐·lease 데이터는 Redis에 넣지 않는다.
- Model Manager의 활성 모델·단계는 model-config 설정 파일 한 곳에 둔다.

### 모듈 구성

| 모듈 | 역할 |
|---|---|
| `common` | 요청/응답 DTO, API 경로 상수만 둔다. 로직 금지 |
| `front-gateway` | 질문 접수, 요청 ID·버전 태그, SSE 스트림 중계, 중복 토큰 필터 |
| `broker` | 접수, 공용 큐, lease, 4대분 상한, 재시도·격리, 복제 |
| `worker` | 크레딧 pull, lease 갱신, 스텝 예산, 엔진 스케줄링, 모델 실행(가짜), 하트비트 |
| `membership` | 하트비트 감지, 스팟 회수 통지 처리, lease 회수, 재보충, 복구 알림, 리더 락 |
| `model-manager` | 모델 교체, 사다리 단계, 품질 게이트, 버전 비율, 롤백 |
| `monitoring` | 지표 수집, 알림, 품질 샘플 전달 |
| `fake-aws` | 스팟 회수 통지 발송, 인스턴스 재생성 요청 처리(예비 Worker에 기동 신호) |
| `client` | Web UI(이용자), AdminConsole(운영자) 최소 화면은 프레임워크 없이 정적 HTML + JavaScript로 만든다. 질문은 긴 입력을 담아야 하므로 `fetch()`의 POST로 보내고, 응답 본문을 SSE 형식 스트림으로 읽는다(`EventSource`는 GET만 되어 쓰지 않는다). API Client(외부 프로그램용)는 같은 API를 쓰는 간단한 Java 클라이언트로 만든다 |

### docker compose 서비스 이름과 포트

| 서비스 | 포트 |
|---|---|
| front-gateway | 8080 |
| broker-primary / broker-replica | 8081 / 8082 |
| worker-1 ~ worker-5 / worker-spare | 8091 ~ 8095 / 8096 |
| membership-1 / membership-2 | 8101 / 8102 |
| model-manager | 8110 |
| monitoring | 8120 |
| fake-aws | 8130 |
| client | 8140 |
| redis | 6379 |

- 컨테이너끼리는 서비스 이름으로 호출한다(예: `http://broker-primary:8081`). 포트 번호는 호스트에서 접근할 때만 쓴다.
- 모든 앱은 Spring Actuator의 `/actuator/health`를 열고, compose healthcheck에 쓴다.

### 실행 모드 (mock / real)

- 클라우드 비용을 최소화하기 위해 기본 실행은 mock 프로필이다. 로컬 docker compose로 전부 돌리고, sd1~sd6 시연도 mock으로 한다.
- 외부 의존(추론 엔진, AWS 스팟, 가중치 저장소 S3)은 Port 인터페이스 하나에 mock 구현과 real 구현을 둔다. EA 클래스는 그대로 두고 외부와 닿는 부분만 Port로 뺀다.

| 외부 의존 | Port | real 구현 | mock 구현 | 사용하는 EA 클래스 |
|---|---|---|---|---|
| 추론 엔진 | `InferenceEnginePort` | `VllmEngineAdapter` | `MockInferenceEngine` | ModelRuntime (execute, loadWeights, generationTest가 위임) |
| AWS 스팟 | `SpotInstancePort` | `SpotInstanceClient` (EA, AWS SDK) | `MockSpotInstanceClient` (fake-aws 호출) | Replenisher |
| 가중치 저장소 | `ModelStorePort` | `ModelStoreDAO` (EA, S3) | `MockModelStoreDAO` (단계별 가짜 가중치 정보: 크기, KV 용량) | ModelConfigController, QualityGate |

- 이 세 Port와 mock·real 구현은 각 모듈의 `adapter` 패키지에 둔다. 단, EA 클래스(SpotInstanceClient, ModelStoreDAO)는 EA 패키지에 둔다.
- 구현 선택은 Spring 프로필(`@Profile("mock")`, `@Profile("real")`)로만 한다. 로직 코드에서 모드를 분기하지 않는다.
- mock 구현도 real 구현과 같은 계약을 지킨다: 같은 입력/출력 타입, 지연, 실패와 타임아웃 동작.
- real 모드는 mock으로 만들 수 없는 숫자를 측정할 때만 쓴다: 모델 적재 메모리(가중치 + KV), GPU당 동시 세션 수, 긴 입력이 섞였을 때의 실제 TTFT p95. 이때 GPU 인스턴스만 띄우고 나머지 앱은 로컬에 둔다.
- AWS 자격 증명, 엔진 주소 같은 real 설정은 `.env`로만 받고 커밋하지 않는다(`.gitignore`).

### 가짜 엔진 (MockInferenceEngine)

- 가짜 엔진은 GPU를 대신하는 부품이다. 전략(C2·T3·U1·R3) 로직은 Worker의 다른 클래스에 있고, 가짜 엔진은 판단을 하지 않는다.
- ModelRuntime(EA)은 그대로 두고, ModelRuntime이 `InferenceEnginePort`를 통해 가짜 엔진을 부른다. 토큰을 StreamPublisher로 보내는 일과 가중치 적재 요청은 ModelRuntime이 한다.
- 가짜 엔진은 `StepPlan`을 받아 스텝 시간만큼 지연한 뒤, 스텝에 담긴 요청의 토큰과 Prefill 완료 결과(`StepResult`)를 돌려준다.
- 스텝 시간 = 기본 시간 + a × (이번 스텝의 Prefill 조각 토큰 수) + b × (이번 스텝의 Decode 요청 수). 기본 시간, a, b는 설정값으로 받는다.
- 가짜 엔진이 하면 안 되는 것 (하면 전략이 바뀐다):
  - StepPlan을 무시하고 긴 입력을 한 번에 처리하기
  - 짧은 요청을 엔진이 알아서 먼저 처리하기
  - 남은 여유를 엔진이 직접 Broker에 알리기 (크레딧 계산은 CreditManager의 일)
- real 모드의 추론 엔진은 엔진 자체의 chunked prefill을 쓰고, B는 엔진 시작 설정값(스텝당 최대 토큰 수)으로 넣는다. EngineScheduler·BudgetGuard 로직 검증은 mock에서, 실제 TTFT 측정은 real에서 한다.
- mock과 real은 같은 계약(`execute(StepPlan): StepResult`, `loadWeights`, `generationTest`)을 지킨다. 프로필만 바꾸면 위쪽 코드는 그대로 동작해야 한다. 계약 상세는 `docs/design.md` 5절 "추론 엔진 계약".

### 가짜 AWS

- mock 모드에서 AWS는 `fake-aws` 앱으로 대체한다. 실제 AWS SDK는 real 구현에서만 쓴다.
- `fake-aws`가 하는 일은 세 가지다.
  - 시연 명령을 받으면 Membership에 스팟 회수 통지(대상 워커 ID)를 보낸다. Membership 두 인스턴스 모두에 보내도 된다(인출 중지는 멱등).
  - 인스턴스 재생성 요청을 받으면 worker-spare에 기동 신호를 보낸다.
  - 기동 신호를 보낸 뒤 Membership에 "인스턴스 기동 완료"(sd3, AWS → Replenisher)를 보낸다. 두 인스턴스 모두에 보내고, 리더만 처리한다(나머지는 무시).

## 3. 서비스 간 통신

- 서비스 간 호출은 HTTP(REST, JSON)로 한다. 클라이언트로 가는 순번 안내와 토큰 스트림은 SSE 형식(`text/event-stream`)으로 한다. 질문 접수는 POST 한 번으로 받고, 그 응답을 SSE 스트림으로 연다(QuestionController `receive(text): SseSession`).
- 브라우저가 직접 부르는 앱(front-gateway, model-manager, monitoring)은 client 출처(`http://localhost:8140`)에 CORS를 연다. 출처는 설정값으로 받는다.
- 요청/응답 DTO와 API 경로 상수는 `common`에만 정의하고, 각 앱에서 같은 DTO를 새로 만들지 않는다. 필드 이름은 camelCase로 통일한다.
- 각 앱의 코드 의존은 `common`에만 한다. 다른 앱 모듈을 의존성으로 추가하지 않는다.
- 다른 앱 호출은 호출하는 앱의 `adapter` 패키지에 있는 Port 인터페이스를 거친다. 구현은 같은 패키지의 RestClient 기반 클래스(`XxxRestAdapter`)로 한다. 예외로 EA에 이미 있는 인터페이스 `FailureNotifier`는 EA대로 `broker.retry`에 두고, 구현 `FailureNotifierRestAdapter`만 `broker.adapter`에 둔다.
- 업무 로직 클래스는 Port 인터페이스만 알고, RestClient나 URL을 직접 다루지 않는다.
- 다른 서비스 주소는 `application.yml` 설정값으로 받는다. 하드코딩하지 않는다.
- 모든 외부 호출에 타임아웃을 둔다. 타임아웃이 나도 기준안보다 나빠지지 않는 경로로 처리한다(예: 응답 없는 대상은 건너뛰고 다음 주기에 다시 시도).
- 같은 요청이 두 번 와도 결과가 같도록(멱등) 만든다: 인출 중지, lease 회수, 대체 서버 요청, 토큰 수신(요청 ID + 순번으로 중복 제거).

### Port 목록

| 호출하는 앱 | Port | 받는 앱의 입구 → 대상 |
|---|---|---|
| front-gateway | AdmissionPort (enqueue, getPosition) | broker AdmissionController |
| front-gateway | ModelConfigPort (비율 조회) | model-manager ModelConfigController → ModelConfig |
| worker | LeasePort (pull, renew, complete) | broker LeaseController → LeaseManager |
| worker | HeartbeatPort | membership HeartbeatController → HealthDetector |
| worker | ModelConfigPort (설정 감지, 드레인·준비 보고) | model-manager ModelConfigController → ModelConfig |
| worker | WeightStorePort (loadWeights) | model-manager ModelConfigController → ModelStorePort (real: ModelStoreDAO) |
| worker | StreamPort (토큰 푸시) | front-gateway StreamController → StreamRelay |
| membership | LeasePort (reclaim) | broker LeaseController → LeaseManager |
| membership | WorkerControlPort (stopPull) | worker WorkerController → WorkerAgent |
| membership | AlertPort | monitoring AlertController → AlertService |
| membership | SpotInstancePort | fake-aws |
| fake-aws | (호출받는 쪽) | membership NoticeController → NoticeHandler (회수 통지), → Replenisher (기동 완료) |
| broker | FailureNotifier | front-gateway StreamController → StreamRelay |
| broker | ReplicaPort (동기 복제) | 복제본 broker 인스턴스 |
| monitoring | QueueMetricsPort | broker QueueMetricsController → SharedQueue |
| monitoring | WorkerMetricsPort | worker WorkerController → EngineScheduler |
| monitoring | QualitySamplePort | model-manager ModelConfigController → QualityGate |

- 브로커 승격 후 재연결은 LeasePort·AdmissionPort 구현 안에서 처리한다(주 브로커 호출 실패 시 복제본 주소로 전환). 호출부에 흩어 놓지 않는다.
- EA에 이미 있는 클래스가 Port 구현 역할을 하면 EA 이름을 그대로 쓰고 Port를 구현하게 한다.
  - QueueBrokerClient → AdmissionPort 구현 (front-gateway)
  - ModelConfigClient → ModelConfigPort 구현 (front-gateway)
  - StreamPublisher → StreamPort 구현 (worker)
  - SpotInstanceClient → SpotInstancePort 구현 (membership)
  - SampleForwarder는 QualitySamplePort를 사용하는 control이다 (monitoring)

## 4. 패키지와 클래스 규칙

- 기본 패키지: `com.llmserving.<모듈명>` (예: `com.llmserving.broker.lease`). 모듈명의 하이픈은 뺀다(front-gateway → `frontgateway`, model-manager → `modelmanager`, fake-aws → `fakeaws`). 패키지별 클래스 목록은 `docs/design.md` 2절에 있다.
- 패키지 구조는 EA Module View를 따른다.
  - 차일드 다이어그램이 있는 broker, worker, membership만 부품 단위 2단 패키지로 나눈다.
    - broker: `admission`, `queue`, `lease`, `retry`, `replication`
    - worker: `agent`, `credit`, `scheduling`, `budget`, `runtime`, `leaserenewal`
    - membership: `detection`, `leader`, `recovery`
  - front-gateway, model-manager, monitoring은 하위 패키지 없이 모듈 패키지 하나에 둔다.
  - client 모듈은 EA의 두 패키지 그대로 `webui`, `apiclient`로 나눈다.
- 구현하면서 추가하는 기술 패키지는 두 개만 허용한다: `adapter`(새로 만드는 Port 인터페이스와 `XxxRestAdapter`), `config`(설정·빈 등록). EA에 있는 클래스는 이 패키지로 옮기지 않고 EA 패키지에 둔다(예: QueueBrokerClient는 front-gateway 패키지에 두고 AdmissionPort를 구현한다).
- 새로 추가하는 REST 입구 클래스는 그 입구가 넘겨주는 대상 클래스와 같은 패키지에 둔다(예: LeaseController는 `broker.lease`). 대상이 여러 패키지에 걸치면 Port 목록 표에서 첫 번째 대상의 패키지에 둔다(WorkerController는 `worker.agent`, NoticeController는 `membership.detection`).

### 클래스 역할 (EA 스테레오타입 기준)

| 스테레오타입 | 해당 클래스 | 하는 일 | 하지 않는 일 |
|---|---|---|---|
| boundary | 화면(WebUI, AdminConsole, SseClient, ApiClient), 외부와 맞닿는 클래스(QueueBrokerClient, ModelConfigClient, StreamPublisher, SpotInstanceClient, ModelStoreDAO, 새로 만드는 `XxxRestAdapter`) | 화면 표시, 다른 앱·외부 시스템 호출 | 업무 판단 |
| control | 입구 클래스(EA의 QuestionController·AdmissionController·ModelSwapController, 새로 만드는 얇은 REST 입구)와 업무 로직 클래스 | 입구: 요청 수신과 위임. 로직: 업무 규칙과 계산 | RestClient 직접 사용 (boundary를 거친다) |
| entity | 데이터 클래스 | 데이터와 그 데이터에 대한 단순 계산 | 다른 클래스 호출 |

- 스테레오타입은 EA에 붙은 것을 그대로 따른다. 새로 만드는 클래스만 위 표로 정한다.
- 이름이 `Controller`로 끝나는 클래스는 입구다. 예외로 Worker의 `BudgetController`는 EA 런타임 박스 이름(Budget Controller)을 그대로 쓴 로직 클래스이며, `BudgetPolicy`를 상속하는 기본 예산 정책이다. 이 밖의 로직 클래스에는 `Controller`를 붙이지 않는다.
- EA의 입구 클래스는 EA에 그려진 호출을 그대로 한다(예: AdmissionController → SharedQueue 적재·순번 조회, QuestionController → RequestIdIssuer·VersionTagger·QueueBrokerClient·StreamRelay). 새로 만드는 얇은 REST 입구는 대상 클래스에 위임만 하고 업무 규칙을 넣지 않는다.
- 업무 규칙은 반드시 control 클래스에 둔다. 예:
  - 동시 실행 4대분 상한 → LeaseLimiter
  - 재시도 상한 도달 시 격리 → RetryCounter / RetryPolicy
  - B는 하한~상한 안에서만 → BudgetGuard
  - 크레딧 = 남은 슬롯·KV·미시작 토큰 기준 → CreditManager
  - 리더만 회수·재보충 실행 → LeaderLock 확인 후 HealthDetector
- 상한값과 시간 값은 설정 파일에서 읽는다. 코드에 숫자를 박지 않는다.
  - broker: lease TTL, 동시 실행 상한(4대분), 재시도 상한(2회)
  - worker: B 하한·상한, 목표 첫 토큰 시간, 가짜 엔진 지연
  - membership: 하트비트 제한 시간, 락 TTL
  - model-manager: 활성 모델, 활성 단계, 목표 단계

## 5. 코드 규칙

### 버전
- Java 21, Spring Boot 3.5.x, Gradle(Kotlin DSL 아님, Groovy `build.gradle`). 버전은 루트 빌드 파일에서만 정하고 모듈에서 따로 지정하지 않는다.
- HTTP 호출은 Spring의 `RestClient`, 테스트는 JUnit 5 + AssertJ, 구조 테스트는 ArchUnit을 쓴다.

### DTO와 클래스 작성
- DTO(앱 사이에 오가는 요청/응답)는 `common`에 Java `record`로 만든다. `common`에는 Lombok을 쓰지 않는다.
- 값이 바뀌지 않는 데이터(StepPlan, StepResult, GateResult 등)도 `record`로 만든다.
- 상태가 바뀌는 entity(Lease, QueueItem, Member, RequestState 등)는 일반 클래스로 만들고, 상태 변경은 의미 있는 메서드로만 한다(예: `lease.extend(newExpiresAt)`, `item.increaseRetry()`). setter를 만들지 않는다.
- Lombok은 앱 모듈에서 `@RequiredArgsConstructor`, `@Getter`, `@Slf4j`만 쓴다. `@Data`, `@Setter`, `@Builder`는 쓰지 않는다.
- 의존성 주입은 생성자 주입만 쓴다.
- 현재 시각은 `java.time.Clock`을 주입받아 쓴다(`System.currentTimeMillis()` 직접 호출 금지). lease 만료, 하트비트 끊김을 테스트에서 시간을 조작해 확인하기 위해서다.

### 동시성과 주기 작업
- 여러 스레드가 동시에 접근하는 메모리 상태(공용 큐, lease 목록, 동시 실행 수, 멤버 목록, 크레딧)는 그 상태를 가진 control 클래스(SharedQueue, LeaseManager, LeaseLimiter, MemberRegistry, CreditManager 등) 안에서만 바꾸고, 그 클래스가 스레드 안전을 책임진다(`ConcurrentHashMap`, 필요한 연산은 `synchronized`로 원자적으로).
- 확인 후 변경이 한 번에 일어나야 하는 연산은 하나의 메서드로 묶는다(예: LeaseLimiter의 "상한 확인 + 허용 수 증가", SharedQueue의 "버전 일치 작업 k건 인출").
- 주기 작업은 `@Scheduled`로 하고, 주기는 설정값으로 받는다: 크레딧 pull, lease 갱신, lease 만료 검사, 하트비트 전송, 장애 감지, 리더 락 갱신, `broker-primary` 락 갱신, 주 브로커 응답 감시, 워커의 설정 변경 감지(ModelConfig 조회), Front Gateway의 대기 순번 갱신 조회, Monitoring의 지표 수집.

### 이름 접미사
| 종류 | 형식 | 예 |
|---|---|---|
| REST 입구 | `XxxController` | LeaseController |
| 다른 앱 호출 인터페이스 | `XxxPort` | LeasePort |
| Port의 HTTP 구현 | `XxxRestAdapter` | LeaseRestAdapter |
| mock 구현 | `MockXxx` | MockInferenceEngine, MockSpotInstanceClient, MockModelStoreDAO |
| 설정 바인딩 | `XxxProperties` | BrokerProperties (broker-config) |
| 요청/응답 DTO | `XxxRequest`, `XxxResponse` | PullRequest, PullResponse |
| 예외 | `XxxException` | LeaseNotFoundException |
- 설계(EA)에 이미 있는 클래스 이름은 이 표보다 EA 이름이 우선이다.

### API 경로
- 형식: `/api/{모듈명}/{자원}` + 동작이 자원 생성·조회로 표현되지 않을 때만 `/{동작}`을 붙인다. `{모듈명}`은 모듈 이름 그대로 쓴다(`front-gateway`, `broker`, `worker`, `membership`, `model-manager`, `monitoring`, `fake-aws`). 모든 경로는 `common`의 `ApiPaths` 상수로 정의한다.
- 예:
  - `POST /api/front-gateway/questions` (질문 접수, 응답은 SSE 스트림)
  - `POST /api/broker/requests` (enqueue)
  - `GET /api/broker/requests/{requestId}/position` (순번 조회)
  - `POST /api/broker/leases/pull`
  - `POST /api/broker/leases/{leaseId}/renew`
  - `POST /api/broker/leases/{leaseId}/complete`
  - `POST /api/broker/workers/{workerId}/reclaim`
  - `POST /api/worker/stop-pull`
  - `POST /api/membership/heartbeats`
  - `POST /api/membership/notices`
  - `POST /api/front-gateway/streams/{requestId}/chunks`
  - `POST /api/front-gateway/failures/{requestId}`
  - `POST /api/model-manager/swaps`, `GET /api/model-manager/swaps/status`
  - `GET /api/monitoring/alerts`

### 에러 응답
- 모든 앱은 `@RestControllerAdvice` 하나로 예외를 `common`의 `ErrorResponse` record(`code`, `message`, `requestId`, `instanceId`, `timestamp`)로 바꿔 응답한다. `code`는 `common`의 `ErrorCode` enum 값이다.
- HTTP 상태 코드:
  - 400: 입력값이 잘못됨
  - 404: 없는 요청·lease·워커
  - 409: 상태 충돌 (내 lease가 아님, 낡은 epoch·펜싱 토큰, 이미 처리됨)
  - 503: 지금 처리할 수 없음 (리더가 아님, 승격 전 복제본, 대상 서비스 응답 없음)
- 접수(enqueue)는 C2에 따라 거절하지 않는다. 대기열이 길어도 429를 쓰지 않고 순번으로 안내한다.

### ID와 시간
- 요청 ID, lease ID는 UUID 문자열이다. 요청 ID는 Front Gateway만 발급한다.
- 워커 ID, 인스턴스 ID는 `INSTANCE_ID` 환경변수 값(예: `worker-1`, `membership-2`)을 그대로 쓴다.
- 시각은 epoch 밀리초 `long`이고 이름은 `~At`으로 끝낸다(`createdAt`, `expiresAt`).
- 기간은 단위를 이름에 붙인다(`leaseTtlSec`, `targetTtftMs`). EA 설정 항목 이름과 같다.

### 설정 키
- 형식: `llm.{모듈}.{항목}`, kebab-case, 단위 포함. 각 모듈의 `XxxProperties`로 바인딩한다.
- 예: `llm.broker.lease-ttl-sec`, `llm.broker.concurrency-cap`, `llm.broker.retry-limit`, `llm.broker.expire-check-interval-ms`, `llm.worker.budget.b-min`, `llm.worker.budget.b-max`, `llm.worker.engine.step-base-ms`, `llm.worker.pull-interval-ms`, `llm.worker.renew-interval-ms`, `llm.worker.heartbeat-interval-ms`, `llm.membership.heartbeat-timeout-sec`, `llm.membership.detect-interval-ms`, `llm.membership.lock-ttl-sec`, `llm.worker.config-poll-interval-ms`, `llm.front-gateway.position-poll-interval-ms`, `llm.monitoring.collect-interval-ms`.

### 로그
- 모든 앱은 SLF4J로 로그를 남기고, MDC에 `instanceId`와 `requestId`를 넣는다. 앱 사이 호출에는 `X-Request-Id` 헤더로 요청 ID를 넘긴다.
- 로그 형식: `[instanceId] [requestId] event=<이벤트명> key=value ...`
- 이벤트명은 영어 소문자 점 표기로 고정한다. 시연에서 요청 흐름을 grep으로 따라갈 수 있어야 한다.
  - 예: `request.enqueued`, `lease.issued`, `lease.renewed`, `lease.expired`, `lease.reclaimed`, `retry.requeued`, `retry.quarantined`, `worker.pull-stopped`, `worker.excluded`, `worker.joined`, `broker.promoted`, `model.swap-started`, `model.rolled-back`

### 언어
- 코드 식별자(클래스, 메서드, 변수, 설정 키, 로그 이벤트명)는 영어로 쓴다.
- 주석, 로그 메시지 본문, 문서, 커밋 메시지는 한국어로 쓴다.

### 커밋과 브랜치
- 커밋 메시지: `[모듈] 타입: 요약` (예: `[broker] feat: lease 4대분 상한 적용`). 타입은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`.
- 브랜치: `타입/모듈-내용` (예: `feat/broker-lease`, `feat/sd3-spot-reclaim`).

## 6. 동작 규칙 (시퀀스 sd1~sd6 요약)

### 요청 처리 (sd1, C2)
- Front Gateway는 요청 ID와 버전 태그(v2 비율 기준)를 붙여 Broker에 enqueue하고, 순번을 SSE로 안내한다.
- Broker AdmissionController는 거절하지 않고 적재하며, 복제가 확인된 뒤 순번을 응답한다.
- Worker CreditManager는 실제 여유로 크레딧 n을 계산하고 n건만 pull한다. Broker는 LeaseLimiter가 허용한 k(≤ n)건만 버전 일치 작업으로 내주고 lease를 발급한다.
- 완료되면 Worker는 complete(ack)를 보낸다.

### 스텝 실행 (sd2, T3)
- EngineScheduler는 매 스텝 Decode 전부 + Prefill 조각(합계 ≤ B)으로 스텝을 구성한다.
- B 결정 순서(런타임과 같음): EngineScheduler → BudgetPolicy `budgetFor(backlog)` → 정책 구현(기본: BudgetController)의 `propose(backlog)` → BudgetGuard `clamp(candidate)`로 하한·상한 적용 → EngineScheduler.
- BudgetPolicy는 추상 클래스이고 `budgetFor()`는 재정의할 수 없는 고정 메서드다(`final`). 정책 구현 클래스는 `propose()`만 바꾼다.
- 첫 토큰과 진척은 EngineEventListener로 CreditManager와 LeaseRenewer에 알린다.

### 스팟 회수와 장애 (sd3, R3)
- Worker Agent는 하트비트를 Membership에 보낸다. Broker에는 보내지 않는다.
- 회수 통지를 받으면 NoticeHandler가 해당 워커의 WorkerAgent에 인출 중지를 보내고, HealthDetector에 회수 예정을 알린다. 워커는 새 pull만 멈추고 진행 중인 답변은 마저 생성한다.
- 서버가 사라져 하트비트가 끊기면 HealthDetector가 워커를 제외한다(T서비스 시작). LeaseReclaimer가 그 워커의 lease를 회수해 미완료 요청을 공용 큐로 되돌리고, 남은 4대가 4대분 상한 안에서 처리한다. LeaseReclaimer는 RecoveryNotifier에 재분배 완료를 알리고, RecoveryNotifier가 Monitoring에 복구 알림(T서비스 기록)을 보낸다.
- HealthDetector가 Replenisher에 재보충을 지시하고(서버 소멸 후), Replenisher가 대체 서버를 요청한다(T재보충 시작).
- 인스턴스 기동 완료를 받으면 새 워커가 모델 적재와 생성 시험을 한다. 통과하면 WorkerAgent가 HealthDetector에 상태를 보고하고, HealthDetector가 합류를 확인한다. Replenisher는 RecoveryNotifier에 합류 완료를 알리고(다시 5대), RecoveryNotifier가 T재보충을 기록한다.
- 리더 락을 가진 Membership 인스턴스만 제외, 회수, 재보충을 실행한다. 대체 서버 요청에는 workerId 기반 중복 방지 키를 넣는다.

### 요청 유발 장애 격리 (sd4, R3 보완)
- LeaseRenewer는 진척이 보고된 요청의 lease만 갱신한다. 진척이 끊기면 갱신을 멈춘다.
- lease가 만료되면 LeaseManager는 RetryCounter에 보고한다. 바로 큐에 다시 넣지 않는다.
- RetryCounter는 재시도 상한 미만이면 재투입하고, 상한에 닿으면 QuarantineQueue로 격리한 뒤 FailureNotifier로 실패를 통지한다.

### 브로커 복제 (sd5, C2 보완)
- 큐와 lease의 모든 변경은 Replicator가 복제본에 동기 복제한 뒤 커밋한다.
- AdmissionController는 복제가 확인된 뒤에 순번을 응답한다.
- 승격은 쿼럼 기반이다(EA BrokerReplica). EA sd5 노트는 실제 배포에서 관리형 기능의 쿼럼 승격을 쓴다고 전제한다. 이 구현에서는 그 동작을 다음 규칙으로 직접 구현한다.
- 표는 주 브로커, 복제본, Redis의 `broker-primary` 락 세 개이고, 둘이 맞아야 쓸 수 있다.
- 규칙:
  1. 평소: 주 브로커는 복제본의 확인을 받은 뒤 커밋한다. 이 경로는 Redis가 없어도 동작한다.
  2. 복제본과 연락이 끊기면: 주 브로커는 `broker-primary` 락을 갱신하고 있는 동안에만 혼자 커밋한다.
  3. 복제본과 연락도 안 되고 락 갱신도 실패하면: 주 브로커는 쓰기를 멈춘다(고립 상태).
  4. 승격: 복제본은 주 브로커와 연락이 끊긴 지 X초가 지났고, 그 상태에서 `broker-primary` 락을 얻었을 때만 승격한다. 락은 주 브로커의 락이 만료돼야 얻을 수 있다. 승격할 때 epoch를 1 올린다.
  5. fencing: 복제 메시지와 lease 응답에 epoch를 붙인다. 받는 쪽(브로커, 워커, Front Gateway)은 현재보다 낮은 epoch가 붙은 메시지를 거부한다.
- 시간 조건: 락 갱신 주기 < 락 TTL ≤ X. 고립된 주 브로커가 먼저 멈추고 그 다음에 복제본이 승격해야 주 브로커가 두 개인 순간이 생기지 않는다. 세 값은 설정으로 받는다(`llm.broker.primary-lock-renew-interval-ms`, `llm.broker.primary-lock-ttl-sec`, `llm.broker.promote-after-sec`).
- 승격 후 큐와 기존 lease는 그대로 유지되고, 워커는 새 주 브로커에서 기존 lease로 계속 처리한다. 새 주 브로커는 복제본 없이 2번 규칙으로 동작한다.

### 모델 교체와 롤백 (sd6, U1)
- 모델 교체는 model-config 한 곳만 수정한다. QualityGate를 통과한 단계만 허용한다.
- 워커는 한 대씩 인출 중지 → 드레인 → 재적재 → 준비 보고 순으로 교체하고, ModelConfig가 v2 비율을 갱신한다.
- 품질 이상이 감지되면 RollbackManager가 ModelConfig를 거쳐 U0로 되돌린다. 교체와 같은 절차를 쓴다.
- Model Manager는 다른 서브시스템을 호출하지 않는다. 교체·롤백 상태는 AdminConsole이 조회한다.

## 7. 작업 방식

- 구현 순서: common → broker + worker 1대 연결 → worker 5대 lease 만료·재투입 → membership 1대(sd3) → 격리(sd4) → membership 2대 리더 락 → broker 복제(sd5) → 모델 교체(sd6).
- 한 번에 한 컴포넌트, 한 시나리오만 작업한다. 맡은 모듈 밖의 파일은 수정하지 말고, 필요하면 알린다.
- `common`의 DTO나 API 경로를 바꿔야 하면 바꾸기 전에 알린다. 모든 앱에 영향이 간다.
- 작업 브랜치는 컴포넌트 또는 시나리오 단위로 만들고 PR로 합친다(이름 형식은 5절).
- 변경 후 반드시 빌드와 테스트를 돌려 통과를 확인한다.

```
./gradlew build
./gradlew test
docker compose up --build
```

## 8. 테스트

- control 클래스의 업무 규칙(4대분 상한, 재시도 상한, B 하한·상한, 크레딧 계산)은 단위 테스트를 둔다.
- ArchUnit으로 다음 규칙을 테스트한다.
  - 각 앱은 다른 앱 모듈을 참조하지 않는다(`common`만 참조).
  - control·entity 클래스는 RestClient를 직접 참조하지 않는다.
  - RestClient는 boundary 클래스(`adapter` 패키지의 `XxxRestAdapter`·`MockXxx`·`VllmEngineAdapter`와 EA의 QueueBrokerClient, ModelConfigClient, StreamPublisher, SpotInstanceClient, ModelStoreDAO)에서만 사용한다.
  - 새로 추가한 얇은 REST 입구 클래스는 boundary 클래스를 직접 참조하지 않는다.
- 시나리오(sd1~sd6)는 docker compose 환경에서 통합 테스트로 확인한다.
