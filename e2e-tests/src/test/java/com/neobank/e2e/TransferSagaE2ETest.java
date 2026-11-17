package com.neobank.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the transfer saga against the REAL stack, through the public API gateway:
 * register -> open accounts -> transfer -> (Kafka saga across account/transaction services) -> final balances.
 *
 * Covers the happy path, idempotent retries (Idempotency-Key), key misuse, a failed debit (insufficient funds,
 * nothing to undo) and the compensation path (credit to a non-existent account must refund the sender).
 *
 * Prerequisites: Docker, and a .env next to docker-compose.yml (run scripts/bootstrap-env.sh once).
 * Run:  mvn -Pe2e verify -pl e2e-tests      (first run builds all images - allow ~10 minutes)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferSagaE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // The gateway is the only entry point; Grafana/Prometheus/Kafka UI are not needed here, so the
    // "observability" compose profile is switched off to keep the stack small enough for a CI runner.
    static ComposeContainer stack;

    private static String baseUrl;
    private static String accessToken;
    private static UUID userId;
    private static UUID accountA;
    private static UUID accountB;
    private static String adminToken;
    private static final String FUNDING_REF_A = "E2E-FUND-A-" + UUID.randomUUID();
    private static final String FUNDING_REF_B = "E2E-FUND-B-" + UUID.randomUUID();

    @BeforeAll
    static void startStack() throws Exception {
        File composeFile = new File("../docker-compose.yml");
        assertThat(new File("../.env"))
                .as("Missing .env - run scripts/bootstrap-env.sh from the repo root first")
                .exists();

        stack = new ComposeContainer(composeFile)
                .withLocalCompose(true)
                .withBuild(true)
                .withEnv("COMPOSE_PROFILES", "")
                // lower the AML reporting threshold so a modest transfer trips the monitoring rules
                .withEnv("AML_LARGE_TRANSACTION_THRESHOLD", "300")
                .withExposedService("api-gateway", 8000,
                        Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(15)));
        stack.start();
        Runtime.getRuntime().addShutdownHook(new Thread(stack::stop));

        baseUrl = "http://" + stack.getServiceHost("api-gateway", 8000) + ":" + stack.getServicePort("api-gateway", 8000);

        // The gateway is up before every downstream service has registered with Eureka, so retry the first calls.
        String email = "e2e-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> registered = eventually(Duration.ofMinutes(5), () -> post("/api/v1/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"Sup3r-secret-pw\",\"fullName\":\"E2E User\"}", null, null),
                r -> r.statusCode() == 201);
        accessToken = cookie(registered, "access_token");
        userId = UUID.fromString(JSON.readTree(registered.body()).get("userId").asText());

        accountA = openAccount("500.00", FUNDING_REF_A);
        accountB = openAccount("100.00", FUNDING_REF_B);
        adminToken = loginAsBootstrapAdmin();
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @Order(1)
    void happyPath_movesMoneyExactlyOnce_andIsReplaySafe() throws Exception {
        String key = "e2e-" + UUID.randomUUID();
        String body = transferBody(accountA, accountB, "25.00");

        HttpResponse<String> first = post("/api/v1/transactions/transfer", body, accessToken, key);
        assertThat(first.statusCode()).isEqualTo(202);
        String txId = JSON.readTree(first.body()).get("id").asText();
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(first.headers().firstValue("X-Request-Id")).isPresent();   // correlation id returned by the gateway

        assertThat(awaitTerminalStatus(txId)).isEqualTo("COMPLETED");
        assertBalance(accountA, "475.00");
        assertBalance(accountB, "125.00");

        // Client retry (timeout / double click) with the same key and payload: same transaction, no second transfer.
        HttpResponse<String> replay = post("/api/v1/transactions/transfer", body, accessToken, key);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(JSON.readTree(replay.body()).get("id").asText()).isEqualTo(txId);

        Thread.sleep(3_000);   // give a (wrongly) duplicated saga time to show up
        assertBalance(accountA, "475.00");
        assertBalance(accountB, "125.00");
    }

    @Test
    @Order(2)
    void sameKeyWithDifferentPayload_isRejectedWith422() throws Exception {
        String key = "e2e-" + UUID.randomUUID();
        assertThat(post("/api/v1/transactions/transfer", transferBody(accountA, accountB, "1.00"), accessToken, key)
                .statusCode()).isEqualTo(202);

        HttpResponse<String> reused = post("/api/v1/transactions/transfer", transferBody(accountA, accountB, "2.00"), accessToken, key);

        assertThat(reused.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(3)
    void transferWithoutIdempotencyKey_isRejectedWith400() throws Exception {
        HttpResponse<String> response = post("/api/v1/transactions/transfer", transferBody(accountA, accountB, "1.00"), accessToken, null);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(4)
    void insufficientFunds_failsTheTransfer_andLeavesBalancesUntouched() throws Exception {
        String balanceBefore = balanceOf(accountA);
        HttpResponse<String> response = post("/api/v1/transactions/transfer",
                transferBody(accountA, accountB, "900.00"), accessToken, "e2e-" + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(202);

        assertThat(awaitTerminalStatus(JSON.readTree(response.body()).get("id").asText())).isEqualTo("FAILED");
        assertThat(balanceOf(accountA)).isEqualTo(balanceBefore);
    }

    @Test
    @Order(5)
    void creditToUnknownAccount_compensatesTheDebit() throws Exception {
        String balanceBefore = balanceOf(accountA);
        HttpResponse<String> response = post("/api/v1/transactions/transfer",
                transferBody(accountA, UUID.randomUUID(), "10.00"), accessToken, "e2e-" + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(202);

        // debit succeeds -> credit fails (no such account) -> debit is reversed -> transfer ends FAILED
        assertThat(awaitTerminalStatus(JSON.readTree(response.body()).get("id").asText())).isEqualTo("FAILED");
        eventually(Duration.ofSeconds(60), () -> balanceOf(accountA), balance -> balance.equals(balanceBefore));
        assertThat(balanceOf(accountA)).isEqualTo(balanceBefore);
    }

    @Test
    @Order(6)
    void theBooksBalance_andNothingIsLeftInSettlement() throws Exception {
        // Every journal balanced, every cached balance equals its postings, and SETTLEMENT (money mid-transfer) is
        // empty once all sagas - including the compensated one - have finished.
        JsonNode trialBalance = eventually(Duration.ofSeconds(90),
                () -> JSON.readTree(get("/api/v1/ledger/trial-balance", adminToken).body()),
                tb -> tb.path("healthy").asBoolean() && settlementBalance(tb).signum() == 0);

        assertThat(trialBalance.path("unbalancedJournals")).isEmpty();
        assertThat(trialBalance.path("driftedAccounts")).isEmpty();
        assertThat(trialBalance.path("stuckInSettlement")).isEmpty();
        for (JsonNode currency : trialBalance.path("totals")) {
            assertThat(currency.path("balanced").asBoolean()).as("currency " + currency.path("currency").asText()).isTrue();
        }
    }

    @Test
    @Order(7)
    void aHoldReservesMoney_soATransferCannotSpendIt_untilTheHoldIsReleased() throws Exception {
        java.math.BigDecimal balance = new java.math.BigDecimal(balanceOf(accountA));
        String holdAmount = balance.subtract(new java.math.BigDecimal("10.00")).toPlainString();

        HttpResponse<String> placed = post("/api/v1/accounts/" + accountA + "/holds",
                "{\"amount\":" + holdAmount + ",\"reference\":\"E2E-HOLD-" + UUID.randomUUID() + "\",\"reason\":\"e2e\"}", adminToken, null);
        assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
        String holdId = JSON.readTree(placed.body()).get("id").asText();

        JsonNode balances = JSON.readTree(get("/api/v1/accounts/" + accountA + "/balance", accessToken).body());
        assertThat(new java.math.BigDecimal(balances.get("availableBalance").asText())).isEqualByComparingTo("10.00");

        // a customer must NOT be able to manage holds
        assertThat(post("/api/v1/accounts/" + accountA + "/holds/" + holdId + "/release", "{}", accessToken, null).statusCode()).isEqualTo(403);

        // 50.00 > the 10.00 available -> the debit step is rejected and the transfer FAILS
        HttpResponse<String> blocked = post("/api/v1/transactions/transfer", transferBody(accountA, accountB, "50.00"), accessToken, "e2e-" + UUID.randomUUID());
        assertThat(awaitTerminalStatus(JSON.readTree(blocked.body()).get("id").asText())).isEqualTo("FAILED");

        assertThat(post("/api/v1/accounts/" + accountA + "/holds/" + holdId + "/release", "{}", adminToken, null).statusCode()).isEqualTo(200);

        HttpResponse<String> allowed = post("/api/v1/transactions/transfer", transferBody(accountA, accountB, "5.00"), accessToken, "e2e-" + UUID.randomUUID());
        assertThat(awaitTerminalStatus(JSON.readTree(allowed.body()).get("id").asText())).isEqualTo("COMPLETED");
    }

    @Test
    @Order(8)
    void reconcilingTheBanksStatementAgainstTheLedger_surfacesExactlyTheDifferences() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String statement = "{\"source\":\"E2E_BANK\",\"statementDate\":\"" + today + "\",\"currency\":\"SGD\",\"lines\":["
                + "{\"reference\":\"" + FUNDING_REF_A + "\",\"direction\":\"INFLOW\",\"amount\":500.00},"
                + "{\"reference\":\"" + FUNDING_REF_B + "\",\"direction\":\"INFLOW\",\"amount\":100.00},"
                + "{\"reference\":\"E2E-GHOST\",\"direction\":\"INFLOW\",\"amount\":25.00}]}";

        HttpResponse<String> uploaded = post("/api/v1/ledger/statements", statement, adminToken, null);
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(201);
        JsonNode run = JSON.readTree(uploaded.body());

        assertThat(run.path("run").path("matched").asInt()).isEqualTo(2);          // both real deposits match the bank's lines
        assertThat(run.path("breaks")).hasSize(1);                                 // ...and only the ghost line is a difference
        JsonNode theBreak = run.path("breaks").get(0);
        assertThat(theBreak.path("breakType").asText()).isEqualTo("MISSING_IN_LEDGER");
        assertThat(theBreak.path("reference").asText()).isEqualTo("E2E-GHOST");

        // the same statement cannot be uploaded twice
        assertThat(post("/api/v1/ledger/statements", statement, adminToken, null).statusCode()).isEqualTo(409);

        // a break stays open until an operator resolves it with a note
        assertThat(post("/api/v1/ledger/reconciliation-breaks/" + theBreak.path("id").asText() + "/resolve",
                "{\"note\":\"e2e: ghost line is test data\"}", adminToken, null).statusCode()).isEqualTo(200);

        // customers have no access to back-office endpoints
        assertThat(get("/api/v1/ledger/trial-balance", accessToken).statusCode()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- compliance

    @Test
    @Order(9)
    void aLargeTransfer_raisesAnAmlCase_andTheMakerCheckerRuleBlocksSelfApproval() throws Exception {
        HttpResponse<String> transfer = post("/api/v1/transactions/transfer", transferBody(accountA, accountB, "350.00"), accessToken, "e2e-" + UUID.randomUUID());
        assertThat(transfer.statusCode()).isEqualTo(202);
        assertThat(awaitTerminalStatus(JSON.readTree(transfer.body()).get("id").asText())).isEqualTo("COMPLETED");

        // transaction monitoring consumes the completed debit asynchronously -> a case appears for the sender's account
        JsonNode theCase = eventually(Duration.ofSeconds(90), () -> findOpenCase(accountA, "TRANSACTION_MONITORING"), c -> c != null);
        String caseId = theCase.path("id").asText();
        assertThat(theCase.path("summary").asText()).contains("LARGE_TRANSACTION");
        assertThat(theCase.path("status").asText()).isEqualTo("OPEN");

        assertThat(post("/api/v1/compliance/cases/" + caseId + "/assign", "{}", adminToken, null).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/compliance/cases/" + caseId + "/escalate", "{\"note\":\"e2e: needs approval\"}", adminToken, null).statusCode()).isEqualTo(200);

        // the same person escalated, so they cannot also approve filing a SAR (four-eyes)
        HttpResponse<String> selfApproval = post("/api/v1/compliance/cases/" + caseId + "/close",
                "{\"disposition\":\"SAR_FILED\",\"note\":\"self approval\"}", adminToken, null);
        assertThat(selfApproval.statusCode()).as(selfApproval.body()).isEqualTo(409);
        assertThat(selfApproval.body()).contains("Four-eyes");

        assertThat(post("/api/v1/compliance/cases/" + caseId + "/close",
                "{\"disposition\":\"NO_SUSPICION\",\"note\":\"e2e: reviewed, legitimate\"}", adminToken, null).statusCode()).isEqualTo(200);

        JsonNode closed = JSON.readTree(get("/api/v1/compliance/cases/" + caseId, adminToken).body());
        assertThat(closed.path("summary").path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("history")).hasSizeGreaterThanOrEqualTo(4);       // opened, assigned, escalated, closed (+ alert notes)
        assertThat(get("/api/v1/compliance/cases", accessToken).statusCode()).isEqualTo(403);   // customers have no access
    }

    @Test
    @Order(10)
    void aSanctionsListedCustomer_isFrozenAtOnboarding_andTheFreezeStopsMoneyReachingThem() throws Exception {
        String csv = "list_source,external_id,name,aliases,country,entity_type\n"
                + "E2E_LIST,1,Zorgon Blackwater,Z. Blackwater,XX,INDIVIDUAL\n";
        HttpResponse<String> imported = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/compliance/watchlist/import"))
                .header("Content-Type", "text/csv").header("Authorization", "Bearer " + adminToken)
                .POST(HttpRequest.BodyPublishers.ofString(csv)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);

        // an account whose owner name is on the list (spelled differently: case + word order do not matter)
        HttpResponse<String> opened = post("/api/v1/accounts", "{\"ownerId\":\"" + userId + "\",\"ownerName\":\"BLACKWATER, zorgon\","
                + "\"accountType\":\"SAVINGS\",\"currency\":\"SGD\",\"openingBalance\":0}", accessToken, null);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        UUID listedAccount = UUID.fromString(JSON.readTree(opened.body()).get("id").asText());

        // screening runs off the account.opened event: a CRITICAL case opens and compliance freezes the account
        JsonNode sanctionsCase = eventually(Duration.ofSeconds(90), () -> findOpenCase(listedAccount, "SANCTIONS_SCREENING"), c -> c != null);
        assertThat(sanctionsCase.path("priority").asText()).isEqualTo("CRITICAL");
        assertThat(sanctionsCase.path("accountFrozen").asBoolean()).isTrue();
        eventually(Duration.ofSeconds(90), () -> accountStatus(listedAccount), status -> status.equals("FROZEN"));

        // a transfer to the frozen account cannot complete: the credit is refused, so the sender's debit is compensated
        String before = balanceOf(accountA);
        HttpResponse<String> toFrozen = post("/api/v1/transactions/transfer", transferBody(accountA, listedAccount, "20.00"), accessToken, "e2e-" + UUID.randomUUID());
        assertThat(awaitTerminalStatus(JSON.readTree(toFrozen.body()).get("id").asText())).isEqualTo("FAILED");
        eventually(Duration.ofSeconds(60), () -> balanceOf(accountA), b -> b.equals(before));

        // clearing a hit that froze the account needs four-eyes: the escalator cannot also clear it
        String caseId = sanctionsCase.path("id").asText();
        assertThat(post("/api/v1/compliance/cases/" + caseId + "/escalate", "{\"note\":\"e2e: looks like a different person\"}", adminToken, null).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/compliance/cases/" + caseId + "/close",
                "{\"disposition\":\"FALSE_POSITIVE\",\"note\":\"self approval\"}", adminToken, null).statusCode()).isEqualTo(409);
        // confirming the match is allowed and keeps the account frozen
        assertThat(post("/api/v1/compliance/cases/" + caseId + "/close",
                "{\"disposition\":\"ACCOUNT_RESTRICTED\",\"note\":\"e2e: match confirmed\"}", adminToken, null).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(get("/api/v1/compliance/screening/parties/" + listedAccount, adminToken).body()).path("status").asText())
                .isEqualTo("CONFIRMED_MATCH");
        assertThat(accountStatus(listedAccount)).isEqualTo("FROZEN");
    }

    @Test
    @Order(11)
    void aCloseButNotIdenticalName_goesToHumanReviewWithoutFreezing_andCanBeClearedAsAFalsePositive() throws Exception {
        String csv = "list_source,external_id,name,aliases,country,entity_type\nE2E_LIST,2,Quillon Fairweather,,,\n";
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/compliance/watchlist/import?mode=DELTA"))
                .header("Content-Type", "text/csv").header("Authorization", "Bearer " + adminToken)
                .POST(HttpRequest.BodyPublishers.ofString(csv)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        HttpResponse<String> opened = post("/api/v1/accounts", "{\"ownerId\":\"" + userId + "\",\"ownerName\":\"Quillon Fairweathers\","
                + "\"accountType\":\"SAVINGS\",\"currency\":\"SGD\",\"openingBalance\":0}", accessToken, null);
        UUID lookalike = UUID.fromString(JSON.readTree(opened.body()).get("id").asText());

        JsonNode review = eventually(Duration.ofSeconds(90), () -> findOpenCase(lookalike, "SANCTIONS_SCREENING"), c -> c != null);
        assertThat(review.path("priority").asText()).isEqualTo("HIGH");
        assertThat(review.path("accountFrozen").asBoolean()).isFalse();           // only an exact name freezes automatically
        assertThat(accountStatus(lookalike)).isEqualTo("ACTIVE");

        // no money was frozen, so clearing it needs no second person
        assertThat(post("/api/v1/compliance/cases/" + review.path("id").asText() + "/close",
                "{\"disposition\":\"FALSE_POSITIVE\",\"note\":\"e2e: different person - DOB differs\"}", adminToken, null).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(get("/api/v1/compliance/screening/parties/" + lookalike, adminToken).body()).path("status").asText())
                .isEqualTo("CLEARED_FALSE_POSITIVE");
        assertThat(JSON.readTree(post("/api/v1/compliance/screening/name", "{\"name\":\"Jane Nobody\"}", adminToken, null).body())
                .path("outcome").asText()).isEqualTo("CLEAR");
    }

    @Test
    @Order(12)
    void theAuditTrailIsATamperEvidentHashChain_coveringTheComplianceEventsToo() throws Exception {
        JsonNode verification = eventually(Duration.ofSeconds(60),
                () -> JSON.readTree(get("/api/v1/audit/chain/verify", adminToken).body()),
                v -> v.path("checked").asLong() >= 10);

        assertThat(verification.path("valid").asBoolean()).as(verification.toString()).isTrue();
        assertThat(verification.path("firstBreak").isNull()).isTrue();
        JsonNode head = JSON.readTree(get("/api/v1/audit/chain/head", adminToken).body());
        assertThat(head.path("sequence").asLong()).isGreaterThanOrEqualTo(verification.path("checked").asLong());
        assertThat(head.path("hash").asText()).hasSize(64);
        assertThat(get("/api/v1/audit/chain/verify", accessToken).statusCode()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- helpers

    private static UUID openAccount(String openingBalance, String fundingReference) throws Exception {
        HttpResponse<String> response = post("/api/v1/accounts", "{\"ownerId\":\"" + userId + "\",\"ownerName\":\"E2E User\","
                + "\"accountType\":\"SAVINGS\",\"currency\":\"SGD\",\"openingBalance\":" + openingBalance
                + ",\"fundingReference\":\"" + fundingReference + "\"}", accessToken, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(response.body()).get("id").asText());
    }

    /** The first admin comes from the .env that scripts/bootstrap-env.sh generated (there is no built-in admin credential). */
    private static String loginAsBootstrapAdmin() throws Exception {
        List<String> lines = Files.readAllLines(new File("../.env").toPath());
        String email = envValue(lines, "ADMIN_BOOTSTRAP_EMAIL");
        String password = envValue(lines, "ADMIN_BOOTSTRAP_PASSWORD");
        HttpResponse<String> login = eventually(Duration.ofMinutes(2), () -> post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null, null), r -> r.statusCode() == 200);
        return cookie(login, "access_token");
    }

    private static String envValue(List<String> lines, String key) {
        return lines.stream().filter(l -> l.startsWith(key + "=")).map(l -> l.substring(key.length() + 1).trim()).findFirst()
                .orElseThrow(() -> new AssertionError(key + " missing from ../.env"));
    }

    private static java.math.BigDecimal settlementBalance(JsonNode trialBalance) {
        for (JsonNode gl : trialBalance.path("glAccounts")) {
            if ("SETTLEMENT".equals(gl.path("code").asText()) && "SGD".equals(gl.path("currency").asText())) {
                return new java.math.BigDecimal(gl.path("balance").asText());
            }
        }
        return java.math.BigDecimal.ZERO;
    }

    /** The open case of a type for an account, or null if there is none yet. */
    private static JsonNode findOpenCase(UUID accountId, String type) throws Exception {
        JsonNode page = JSON.readTree(get("/api/v1/compliance/cases?size=100", adminToken).body());
        for (JsonNode c : page.path("content")) {
            if (accountId.toString().equals(c.path("subjectAccountId").asText()) && type.equals(c.path("caseType").asText())) {
                return c;
            }
        }
        return null;
    }

    private static String accountStatus(UUID accountId) throws Exception {
        return JSON.readTree(get("/api/v1/accounts/" + accountId, adminToken).body()).path("status").asText();
    }

    private static String transferBody(UUID from, UUID to, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amount\":" + amount + ",\"currency\":\"SGD\"}";
    }

    private static String awaitTerminalStatus(String txId) {
        return eventually(Duration.ofSeconds(90), () -> {
            HttpResponse<String> r = get("/api/v1/transactions/" + txId, accessToken);
            return JSON.readTree(r.body()).path("status").asText();
        }, status -> status.equals("COMPLETED") || status.equals("FAILED"));
    }

    private static String balanceOf(UUID accountId) throws Exception {
        JsonNode node = JSON.readTree(get("/api/v1/accounts/" + accountId + "/balance", accessToken).body());
        return new java.math.BigDecimal(node.get("balance").asText()).setScale(2).toPlainString();
    }

    private static void assertBalance(UUID accountId, String expected) throws Exception {
        assertThat(balanceOf(accountId)).isEqualTo(expected);
    }

    private static HttpResponse<String> post(String path, String json, String token, String idempotencyKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Tokens are issued as httpOnly cookies; the gateway also accepts them as a Bearer header, which is simpler to send here. */
    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1).split(";", 2)[0])
                .findFirst().orElseThrow(() -> new AssertionError("No " + name + " cookie in login response"));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T eventually(Duration timeout, ThrowingSupplier<T> action, java.util.function.Predicate<T> done) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = null;
        Exception lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                last = action.get();
                if (done.test(last)) {
                    return last;
                }
            } catch (Exception e) {
                lastError = e;   // services still starting - keep trying
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        }
        throw new AssertionError("Condition not met within " + timeout + "; last value: " + last, lastError);
    }
}
