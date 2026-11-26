package com.llmserving.frontgateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.llmserving.common.ApiPaths;
import com.llmserving.frontgateway.adapter.AdmissionPort;
import com.llmserving.frontgateway.adapter.ModelConfigPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS 출처 목록: 정확한 출처와 패턴(클라우드 서버처럼 공인 IP가 바뀌는 경우의 http://*:8140)을 함께 받는다.
 */
@SpringBootTest(properties = {"llm.front-gateway.position-poll-interval-ms=600000",
        "llm.front-gateway.ratio-poll-interval-ms=600000",
        "llm.front-gateway.cors-origins=http://localhost:8140,http://*:8140"})
@AutoConfigureMockMvc
class CorsTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AdmissionPort admissionPort;

    @MockitoBean
    private ModelConfigPort modelConfigPort;

    private org.springframework.test.web.servlet.ResultActions preflight(String origin) throws Exception {
        return mvc.perform(options(ApiPaths.FrontGateway.QUESTIONS)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST"));
    }

    @Test
    void 로컬_출처를_허용한다() throws Exception {
        preflight("http://localhost:8140").andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8140"));
    }

    @Test
    void 공인_IP가_바뀌어도_8140_출처는_허용한다() throws Exception {
        preflight("http://3.35.120.7:8140").andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://3.35.120.7:8140"));
    }

    @Test
    void 다른_포트의_출처는_거부한다() throws Exception {
        preflight("http://3.35.120.7:9999").andExpect(status().isForbidden());
    }
}
