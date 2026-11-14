package com.llmserving.membership.adapter;

import com.llmserving.common.AlertRequest;

/** monitoring AlertService.raise 호출 포트 (RecoveryNotifier). */
public interface AlertPort {

    void raise(AlertRequest alert);
}
