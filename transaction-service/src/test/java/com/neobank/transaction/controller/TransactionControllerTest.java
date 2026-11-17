package com.neobank.transaction.controller;

import com.neobank.transaction.client.AccountClient;
import com.neobank.transaction.domain.Transaction;
import com.neobank.transaction.dto.TransactionResponse;
import com.neobank.transaction.dto.TransferRequest;
import com.neobank.transaction.repository.TransactionRepository;
import com.neobank.transaction.service.TransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    @Mock private TransferService transferService;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountClient accountClient;
    @InjectMocks private TransactionController controller;

    private final UUID caller = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(caller.toString()).build();
    private final TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("5.00"), "SGD", null);
    private final Transaction tx = Transaction.initiate(request.fromAccountId(), request.toAccountId(), request.amount(), "SGD");

    private MockHttpServletRequest httpRequest() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("Authorization", "Bearer t");
        return r;
    }

    @Test
    void newTransfer_returns202WithoutReplayHeader() {
        when(transferService.initiateTransfer(request, caller, "Bearer t", "key-1"))
                .thenReturn(new TransferService.TransferResult(tx, false));

        ResponseEntity<TransactionResponse> response = controller.transfer(request, "key-1", jwt, httpRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().containsKey("Idempotent-Replayed")).isFalse();
    }

    @Test
    void replayedTransfer_returns202WithReplayHeader() {
        when(transferService.initiateTransfer(request, caller, "Bearer t", "key-1"))
                .thenReturn(new TransferService.TransferResult(tx, true));

        ResponseEntity<TransactionResponse> response = controller.transfer(request, "key-1", jwt, httpRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void lostIdempotencyRace_isAnsweredFromTheWinnersTransaction() {
        when(transferService.initiateTransfer(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_tx_requester_idempotency_key"));
        when(transferService.replayExisting(request, caller, "key-1"))
                .thenReturn(new TransferService.TransferResult(tx, true));

        ResponseEntity<TransactionResponse> response = controller.transfer(request, "key-1", jwt, httpRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        verify(transferService).replayExisting(request, caller, "key-1");
    }
}
