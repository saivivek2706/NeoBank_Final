package com.neobank.fraud.websocket;

import com.neobank.fraud.domain.FraudAlert;
import com.neobank.fraud.dto.FraudAlertResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FraudAlertNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(FraudAlert alert) {
        messagingTemplate.convertAndSend("/topic/fraud-alerts", FraudAlertResponse.from(alert));
    }
}
