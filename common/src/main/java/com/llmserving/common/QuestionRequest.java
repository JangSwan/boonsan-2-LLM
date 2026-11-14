package com.llmserving.common;

/**
 * 질문 접수 본문 (POST /api/front-gateway/questions → QuestionController.receive(text)).
 * 긴 입력을 담으므로 POST 본문으로 받는다.
 *
 * @param text 질문 본문
 */
public record QuestionRequest(String text) {
}
