package com.llmserving.frontgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmserving.common.ApiPaths;
import com.llmserving.common.QuestionRequest;
import com.llmserving.common.RatioResponse;
import com.llmserving.common.Request;
import com.llmserving.common.StreamChunk;
import com.llmserving.frontgateway.adapter.AdmissionPort;
import com.llmserving.frontgateway.adapter.ModelConfigPort;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * sd1 접수 → 순번 안내 → 토큰 중계 → 종료, sd4 실패 통지를 HTTP로 확인한다.
 */
@SpringBootTest(properties = {"llm.front-gateway.position-poll-interval-ms=600000",
        "llm.front-gateway.ratio-poll-interval-ms=600000"})
@AutoConfigureMockMvc
class QuestionFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdmissionPort admissionPort;

    @MockitoBean
    private ModelConfigPort modelConfigPort;

    private final AtomicReference<Request> enqueued = new AtomicReference<>();

    private MvcResult ask(String text) throws Exception {
        when(modelConfigPort.currentRatio()).thenReturn(new RatioResponse(0, "v1", null));
        when(admissionPort.enqueue(any())).thenAnswer(inv -> {
            enqueued.set(inv.getArgument(0));
            return 3;
        });
        return mvc.perform(post(ApiPaths.FrontGateway.QUESTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(new QuestionRequest(text))))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private void push(StreamChunk chunk) throws Exception {
        mvc.perform(post(ApiPaths.FrontGateway.STREAM_CHUNKS, chunk.requestId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chunk)))
                .andExpect(status().isAccepted());
    }

    @Test
    void 질문을_받아_요청ID와_버전을_붙여_적재하고_순번을_안내한_뒤_토큰을_중복없이_중계하고_닫는다() throws Exception {
        MvcResult result = ask("가".repeat(400));

        Request request = enqueued.get();
        assertThat(request.versionTag()).isEqualTo("v1");
        assertThat(request.inputTokens()).isEqualTo(100);
        assertThat(request.requestId()).hasSize(36);

        String id = request.requestId();
        push(new StreamChunk(id, 0, "안녕", true, false));
        push(new StreamChunk(id, 0, "안녕", true, false));   // lease 경계 중복
        push(new StreamChunk(id, 1, "하세요", false, true));

        String body = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:position").contains("\"position\":3");
        assertThat(body.split("event:token", -1)).hasSize(3);   // 토큰 2개
        assertThat(body).contains("안녕").contains("하세요");
    }

    @Test
    void 재시도한_답이_더_짧아_마지막_조각이_걸러져도_SSE를_닫는다() throws Exception {
        MvcResult result = ask("다시 생성되는 질문");
        String id = enqueued.get().requestId();

        push(new StreamChunk(id, 0, "첫", true, false));      // 1회차: 세 조각까지 보내고 엔진에서 사라짐
        push(new StreamChunk(id, 1, "시도", false, false));
        push(new StreamChunk(id, 2, "답", false, false));
        push(new StreamChunk(id, 0, "다시", true, false));    // 2회차: 더 짧게 끝남 (마지막 순번 1 ≤ 2)
        push(new StreamChunk(id, 1, "", false, true));

        String body = mvc.perform(asyncDispatch(result)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body.split("event:token", -1)).hasSize(5);   // 1회차 3개 + 마지막 표시 1개
        assertThat(body).contains("\"isLast\":true").doesNotContain("다시");
    }

    @Test
    void 격리된_요청은_실패_이벤트를_받고_닫힌다() throws Exception {
        MvcResult result = ask("엔진을 죽이는 질문");
        String id = enqueued.get().requestId();

        mvc.perform(post(ApiPaths.FrontGateway.FAILURES, id)).andExpect(status().isAccepted());

        String body = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:failure").contains("REQUEST_QUARANTINED");
    }

    @Test
    void 빈_질문은_400으로_응답한다() throws Exception {
        mvc.perform(post(ApiPaths.FrontGateway.QUESTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 대기_중이면_순번_갱신을_조회해_바뀐_순번만_안내한다() throws Exception {
        ask("질문");
        String id = enqueued.get().requestId();
        when(admissionPort.getPosition(id)).thenReturn(1);

        mvc.perform(post(ApiPaths.FrontGateway.STREAM_CHUNKS, "other")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new StreamChunk("other", 0, "x", true, true))));

        // 스케줄 주기를 길게 두었으므로 직접 부른다
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                mvc.getDispatcherServlet().getWebApplicationContext().getBean(QuestionController.class),
                "refreshPositions");
        verify(admissionPort).getPosition(id);
    }
}
