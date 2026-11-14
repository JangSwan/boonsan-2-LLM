package com.llmserving.frontgateway;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** 요청 ID 발급 (EA RequestIdIssuer). 요청 ID는 Front Gateway만 발급한다. */
@Component
public class RequestIdIssuer {

    public String issue() {
        return UUID.randomUUID().toString();
    }
}
