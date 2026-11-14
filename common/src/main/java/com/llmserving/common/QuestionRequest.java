package com.llmserving.common;

/**
 * 질문 접수 요청 (Web UI, API Client → Front Gateway). 응답은 SSE 스트림이다.
 *
 * @param text 질문 본문 (긴 입력 포함)
 */
public record QuestionRequest(String text) {
}
