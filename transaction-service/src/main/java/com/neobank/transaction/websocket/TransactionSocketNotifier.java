package com.neobank.transaction.websocket;

import com.neobank.transaction.domain.Transaction;
import com.neobank.transaction.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Fires after every saga state transition (INITIATED -> DEBIT_PENDING ->
 * CREDIT_PENDING -> COMPLETED, or any FAILED/COMPENSATING branch) so the
 * frontend can show the transfer progressing live instead of polling.
 */
@Component
@RequiredArgsConstructor
public class TransactionSocketNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    public void notifyStatus(Transaction tx) {
        TransactionResponse payload = TransactionResponse.from(tx);
        messagingTemplate.convertAndSend("/topic/transactions/" + tx.getId(), payload);
        messagingTemplate.convertAndSend("/topic/accounts/" + tx.getFromAccountId(), payload);
        messagingTemplate.convertAndSend("/topic/accounts/" + tx.getToAccountId(), payload);
    }
}
