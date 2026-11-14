package com.llmserving.monitoring;

import com.llmserving.common.AlertRequest;
import com.llmserving.common.AlertResponse;
import com.llmserving.common.ApiPaths;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AlertService의 REST 입구 (Membership AlertPort, AdminConsole 조회). 위임만 한다. */
@RestController
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @PostMapping(ApiPaths.Monitoring.ALERTS)
    public ResponseEntity<Void> raise(@RequestBody AlertRequest request) {
        alertService.raise(new AlertEvent(request.kind(), request.message(), request.at()));
        return ResponseEntity.accepted().build();
    }

    @GetMapping(ApiPaths.Monitoring.ALERTS)
    public List<AlertResponse> alerts() {
        return alertService.getAlerts().stream()
                .map(a -> new AlertResponse(a.kind(), a.message(), a.at())).toList();
    }
}
