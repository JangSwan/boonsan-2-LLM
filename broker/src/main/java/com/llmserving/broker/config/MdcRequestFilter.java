package com.llmserving.broker.config;

import com.llmserving.common.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 들어오는 요청의 X-Request-Id 헤더와 인스턴스 ID를 MDC에 넣는다 (CLAUDE.md 5절 "로그").
 */
@Component
@RequiredArgsConstructor
public class MdcRequestFilter extends OncePerRequestFilter {

    public static final String MDC_INSTANCE_ID = "instanceId";
    public static final String MDC_REQUEST_ID = "requestId";

    private final BrokerProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(MDC_INSTANCE_ID, properties.instanceId());
        String requestId = request.getHeader(ApiPaths.HEADER_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_INSTANCE_ID);
        }
    }
}
