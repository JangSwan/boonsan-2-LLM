package com.llmserving.worker.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * worker 설정 (budget-config 포함). 상한값과 시간 값은 여기서 읽는다.
 *
 * @param instanceId           워커 ID (INSTANCE_ID)
 * @param startOnBootSignal    true면 기동 신호를 받기 전까지 하트비트도 pull도 하지 않는다 (worker-spare)
 * @param maxSlots             워커당 동시 실행 슬롯
 * @param pullIntervalMs       크레딧 pull 주기
 * @param renewIntervalMs      lease 갱신 주기
 * @param heartbeatIntervalMs  하트비트 전송 주기
 * @param configPollIntervalMs 설정 변경 감지(ModelConfig 조회) 주기
 * @param progressTimeoutMs    진척 보고가 이 시간 동안 없으면 lease 갱신을 멈춘다
 * @param lastDrainQuietMs     현재 버전의 마지막 워커가 드레인할 때, 이 시간 동안 인출할 작업이 없으면 멈춘다
 * @param brokerUrls           broker 주소 (주 브로커, 복제본 순)
 * @param membershipUrls       membership 주소 (하트비트는 두 인스턴스 모두에 보낸다)
 * @param modelManagerUrl      model-manager 주소
 * @param frontGatewayUrl      front-gateway 주소 (토큰 푸시)
 * @param streamLanes          토큰 푸시 병렬 줄 수 (같은 요청은 같은 줄로 보내 순서를 지킨다)
 * @param budget               budget-config (EA): B 하한, 상한, 목표 첫 토큰 시간
 * @param credit               크레딧 계산 기준
 * @param engine               추론 엔진 (mock 지연, real 주소)
 */
@ConfigurationProperties("llm.worker")
public record WorkerProperties(
        String instanceId,
        boolean startOnBootSignal,
        int maxSlots,
        long pullIntervalMs,
        long renewIntervalMs,
        long heartbeatIntervalMs,
        long configPollIntervalMs,
        long progressTimeoutMs,
        long lastDrainQuietMs,
        List<String> brokerUrls,
        List<String> membershipUrls,
        String modelManagerUrl,
        String frontGatewayUrl,
        int streamLanes,
        Budget budget,
        Credit credit,
        Engine engine) {

    /**
     * budget-config.
     *
     * @param bMin         B 하한 (토큰)
     * @param bMax         B 상한 (토큰)
     * @param targetTtftMs 목표 첫 토큰 시간
     * @param stepTargetMs 스텝 한 번의 목표 시간. 밀린 Prefill을 목표 시간 안에 끝내려면 스텝당 얼마를 읽어야 하는지 계산에 쓴다
     */
    public record Budget(int bMin, int bMax, int targetTtftMs, int stepTargetMs) {
        public Budget {
            if (bMin <= 0 || bMax < bMin) {
                throw new IllegalArgumentException("budget: 0 < b-min ≤ b-max 이어야 한다");
            }
        }
    }

    /**
     * @param kvGbPerRequest     요청 1건이 쓰는 KV 용량 추정 (GB)
     * @param maxUnstartedTokens 미시작 Prefill 토큰이 이 값 이상이면 더 당기지 않는다
     */
    public record Credit(double kvGbPerRequest, int maxUnstartedTokens) {
    }

    /**
     * @param stepBaseMs         가짜 엔진 스텝 기본 시간
     * @param prefillMsPerToken  a: Prefill 조각 토큰 1개당 시간
     * @param decodeMsPerRequest b: Decode 요청 1건당 시간
     * @param idleWaitMs         엔진 루프가 할 일이 없을 때 쉬는 시간
     * @param outputTokensMin    가짜 엔진의 요청별 출력 토큰 수 하한
     * @param outputTokensMax    가짜 엔진의 요청별 출력 토큰 수 상한
     * @param charsPerToken      입력 토큰 수 추정 (Front Gateway와 같은 값)
     * @param crashMarker        이 문자열이 든 질문은 가짜 엔진을 비정상 종료시킨다 (sd4 장애 주입)
     * @param crashRestartMs     엔진 비정상 종료 후 다시 쓸 수 있을 때까지의 시간
     * @param loadDelayMs        가중치 적재 지연
     * @param vllmUrl            real: vLLM(OpenAI 호환) 주소
     * @param vllmModel          real: 모델 이름
     * @param vllmMaxTokens      real: 요청당 최대 출력 토큰
     */
    public record Engine(long stepBaseMs, double prefillMsPerToken, double decodeMsPerRequest, long idleWaitMs,
                         int outputTokensMin, int outputTokensMax, int charsPerToken, String crashMarker,
                         long crashRestartMs, long loadDelayMs, String vllmUrl, String vllmModel,
                         int vllmMaxTokens) {
    }
}
