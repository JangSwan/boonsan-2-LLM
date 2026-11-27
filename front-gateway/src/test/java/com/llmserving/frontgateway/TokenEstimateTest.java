package com.llmserving.frontgateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 입력 토큰 추정 (트러블슈팅 R4): 한글·한자·가나는 1글자 1토큰, 나머지는 4글자 1토큰.
 */
class TokenEstimateTest {

    @Test
    void 영어는_4글자에_1토큰() {
        assertThat(QuestionController.estimateTokens("abcdefgh", 4, 1.0)).isEqualTo(2);
        assertThat(QuestionController.estimateTokens("abcdefghi", 4, 1.0)).isEqualTo(3);
    }

    @Test
    void 한글은_1글자에_1토큰이고_공백과_문장부호는_4글자에_1토큰() {
        // 한글 11글자 + 공백 4개·마침표 1개(5글자 → 2토큰) = 13
        assertThat(QuestionController.estimateTokens("긴 문서 요약을 부탁합니다. ", 4, 1.0)).isEqualTo(13);
    }

    @Test
    void 한글_긴_입력은_긴_요청_기준을_넘는다() {
        String text = "긴 문서 요약을 부탁합니다. ".repeat(340);
        assertThat(QuestionController.estimateTokens(text, 4, 1.0)).isGreaterThanOrEqualTo(4000);
        // 예전 방식(글자 수 ÷ 4)으로는 약 1,275토큰이라 2,000토큰 기준의 긴 요청으로 분류되지 않았다
        assertThat(QuestionController.estimateTokens(text, 4, 0)).isLessThan(2000);
    }

    @Test
    void 한자와_가나도_넓은_글자로_센다() {
        assertThat(QuestionController.estimateTokens("漢字かなカナ", 4, 1.0)).isEqualTo(6);
    }

    @Test
    void 빈_입력도_최소_1토큰() {
        assertThat(QuestionController.estimateTokens("", 4, 1.0)).isEqualTo(1);
    }
}
