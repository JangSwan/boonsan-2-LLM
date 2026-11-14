package com.llmserving.broker.replication;

import com.llmserving.broker.config.ApiException;
import com.llmserving.common.ErrorCode;
import lombok.Getter;

/** 현재보다 낮은 epoch가 붙은 메시지 (fencing, sd5 규칙 5). 409. */
@Getter
public class StaleEpochException extends ApiException {

    private final long currentEpoch;

    public StaleEpochException(long messageEpoch, long currentEpoch) {
        super(ErrorCode.STALE_EPOCH, "낡은 epoch " + messageEpoch + " (현재 " + currentEpoch + ")");
        this.currentEpoch = currentEpoch;
    }
}
