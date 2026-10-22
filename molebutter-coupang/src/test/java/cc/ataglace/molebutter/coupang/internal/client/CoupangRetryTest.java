package cc.ataglace.molebutter.coupang.internal.client;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.CoupangFailure;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import static cc.ataglace.molebutter.coupang.api.CoupangFailure.Kind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoupangRetryTest {
    final ObjectMapper json = new ObjectMapper();
    final CoupangHttpTransport transport = mock(CoupangHttpTransport.class);
    final CoupangProductClient client = new CoupangProductClient("A-test", "PRIVATE_ACCESS", "PRIVATE_SECRET", json, transport, Clock.systemUTC());
    final String approval = CoupangProductClient.PATH + "/123/approvals";
    static final String PROCESSING = "{\"code\":\"ERROR\",\"message\":\"상품 정보가 등록 또는 수정되고 있습니다. 잠시 후 다시 조회해 주시기 바랍니다.\"}";
    static final String NOT_READY = "{\"code\":\"ERROR\",\"message\":\"'임시저장' 상태의 상품만 승인 요청 가능합니다.\",\"data\":null}";
    static final String OK = "{\"code\":\"SUCCESS\",\"data\":123}";
    CoupangHttpTransport.Response response(int status, String body) { return new CoupangHttpTransport.Response(status, body.getBytes(StandardCharsets.UTF_8)); }
    void replies(CoupangHttpTransport.Response... responses) {
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any()))
                .thenReturn(responses[0], Arrays.copyOfRange(responses, 1, responses.length));
    }
    void calls(int n) { verify(transport, times(n)).send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any()); }

    @Test void allMethodsAndSeparateSessionsWaitTwoSecondsAfterEachResponse() {
        var starts = new ArrayList<Long>(); var finishes = new ArrayList<Long>(); var methods = new ArrayList<String>();
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenAnswer(i -> {
            starts.add(System.nanoTime()); methods.add(i.getArgument(0));
            // A slow response must not consume the required post-response gap.
            if (starts.size() == 1 || starts.size() == 3) Thread.sleep(1100);
            finishes.add(System.nanoTime()); return response(200, OK);
        });
        String items = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/222";
        client.orderRead("/orders", "");
        client.orderWithdrawalRead("{}");
        client.writeSession(() -> client.write("PUT", items + "/prices/1000", "", null));
        client.orderRead(items + "/inventories", "");
        client.writeSession(() -> client.write("PUT", items + "/quantities/3", "", null));
        client.orderRead(items + "/inventories", "");
        client.writeSession(() -> client.write("DELETE", CoupangProductClient.PATH + "/123", "", null));
        client.orderRead("/orders", "");
        assertThat(methods).containsExactly("GET", "POST", "PUT", "GET", "PUT", "GET", "DELETE", "GET");
        for (int n = 1; n < starts.size(); n++)
            assertThat(starts.get(n) - finishes.get(n - 1)).as("response gap before request %s", n + 1)
                    .isGreaterThanOrEqualTo(2_000_000_000L);
        calls(8);
    }
    @Test void finalRejectionAndInvalidResponseStillDelayTheNextOperation() {
        for (var rejected : List.of(response(401, "{}"), response(200, "not-json"),
                response(200, "{\"code\":\"ERROR\",\"message\":\"필수 값 누락\"}"))) {
            var t = mock(CoupangHttpTransport.class); var starts = new ArrayList<Long>(); var finishes = new ArrayList<Long>();
            when(t.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenAnswer(i -> {
                starts.add(System.nanoTime()); finishes.add(System.nanoTime());
                return starts.size() == 1 ? rejected : response(200, OK);
            });
            var c = new CoupangProductClient("A-test", "a", "s", json, t, Clock.systemUTC());
            c.writeSession(() -> c.write("POST", CoupangProductClient.PATH, "", "{}"));
            c.orderRead("/orders", "");
            assertThat(starts).hasSize(2);
            assertThat(starts.get(1) - finishes.get(0)).isGreaterThanOrEqualTo(2_000_000_000L);
        }
    }
    @Test void networkRetryAndLaterOperationAfterAmbiguousWriteShareTheResponseGap() {
        var starts = new ArrayList<Long>(); var finishes = new ArrayList<Long>();
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenAnswer(i -> {
            starts.add(System.nanoTime()); finishes.add(System.nanoTime());
            if (starts.size() == 1) throw new CoupangFailure(NETWORK);
            if (starts.size() == 3) throw new CoupangFailure(TIMEOUT);
            return response(200, OK);
        });
        client.orderRead("/orders", ""); // A read may retry once.
        assertThatThrownBy(() -> client.writeSession(() -> client.write("POST", CoupangProductClient.PATH, "", "{}")))
                .isInstanceOfSatisfying(CoupangFailure.class, e -> assertThat(e.kind()).isEqualTo(TIMEOUT));
        client.orderRead("/orders", ""); // The ambiguous CREATE itself is never repeated.
        for (int n = 1; n < starts.size(); n++)
            assertThat(starts.get(n) - finishes.get(n - 1)).isGreaterThanOrEqualTo(2_000_000_000L);
        calls(4);
        verify(transport, times(1)).send(eq("POST"), eq(CoupangProductClient.PATH), anyString(), anyString(), any(), any());
    }
    @Test void retryGuardReadAlsoDelaysTheRetriedWrite() {
        var starts = new ArrayList<Long>(); var finishes = new ArrayList<Long>(); var methods = new ArrayList<String>();
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenAnswer(i -> {
            starts.add(System.nanoTime()); methods.add(i.getArgument(0)); finishes.add(System.nanoTime());
            return response(200, starts.size() == 1 ? NOT_READY : OK);
        });
        client.writeSession(() -> client.write("PUT", approval, "", null, () -> client.orderRead("/guard", "")));
        assertThat(methods).containsExactly("PUT", "GET", "PUT");
        for (int n = 1; n < starts.size(); n++)
            assertThat(starts.get(n) - finishes.get(n - 1)).isGreaterThanOrEqualTo(2_000_000_000L);
        calls(3);
    }

    @Test void nestedSuccessfulResponsesAlsoDelayApprovalAndAutomaticLookupAfterResponse() {
        var starts=new ArrayList<Long>();var finishes=new ArrayList<Long>();
        when(transport.send(anyString(),anyString(),anyString(),anyString(),nullable(byte[].class),any())).thenAnswer(i->{
            starts.add(System.nanoTime());
            if(starts.size()<3)Thread.sleep(1100);
            String body=starts.size()<3?"{\"code\":200,\"data\":{\"code\":\"SUCCESS\",\"data\":123}}"
                    :"{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":123,\"vendorId\":\"A-test\",\"sellerProductName\":\"합성 상품\",\"statusName\":\"승인대기중\",\"items\":[]}}";
            finishes.add(System.nanoTime());return response(200,body);
        });
        client.writeSession(()->client.write("POST",CoupangProductClient.PATH,"","{}"));
        client.writeSession(()->client.write("PUT",approval,"",null));
        assertThat(client.writeSession(()->client.rawProduct("123")).path("statusName").asString()).isEqualTo("승인대기중");
        assertThat(starts.get(1)-finishes.get(0)).isGreaterThanOrEqualTo(2_000_000_000L);
        assertThat(starts.get(2)-finishes.get(1)).isGreaterThanOrEqualTo(2_000_000_000L);calls(3);
    }
    @Test void cancellationDuringPostApprovalWaitStopsAutomaticLookup() throws Exception {
        replies(response(200,OK));client.writeSession(()->client.write("PUT",approval,"",null));
        String request="10000000-1000-4000-8000-100000000098";var waiting=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->client.operation(1L,request,()->client.writeSession(()->{waiting.countDown();return client.rawProduct("123");})));
            assertThat(waiting.await(2,TimeUnit.SECONDS)).isTrue();client.cancel(1L,request);
            assertThatThrownBy(()->future.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(CoupangFailure.class);calls(1);
        }finally{pool.shutdownNow();}
    }

    @Test void approvalRejectionRetriesOnceAfterResponseAndUsesFreshSignature() {
        var starts = new ArrayList<Long>(); var finishes = new ArrayList<Long>(); var signatures = new ArrayList<String>();
        var checks = new AtomicInteger();
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenAnswer(i -> {
            starts.add(System.nanoTime()); signatures.add(i.getArgument(3));
            if (starts.size() == 1) Thread.sleep(150);
            finishes.add(System.nanoTime()); return response(200, starts.size() == 1 ? NOT_READY : OK);
        });
        var result = client.writeSession(() -> client.write("PUT", approval, "", null, checks::incrementAndGet));
        assertThat(json.readTree(result.body()).path("code").asString()).isEqualTo("SUCCESS");
        assertThat(starts.get(1) - finishes.get(0)).isGreaterThanOrEqualTo(2_000_000_000L);
        assertThat(signatures.get(1)).isNotEqualTo(signatures.get(0)); assertThat(checks).hasValue(1); calls(2);
    }
    @Test void oneBudgetIncludesRateLimitThenStateRejectionAndNeverMakesAThirdCall() {
        replies(new CoupangHttpTransport.Response(429, new byte[0], "0"), response(200, NOT_READY), response(200, OK));
        assertThat(client.writeSession(() -> client.write("PUT", approval, "", null)).body()).isEqualTo(NOT_READY.getBytes(StandardCharsets.UTF_8)); calls(2);
    }
    @Test void createPriceAndStockUseTheSameExplicitRejectionPolicy() {
        for (String path : List.of(CoupangProductClient.PATH, "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/222/prices/1000", "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/222/quantities/3")) {
            var t = mock(CoupangHttpTransport.class);
            when(t.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any()))
                    .thenReturn(response(200, PROCESSING), response(200, OK));
            var c = new CoupangProductClient("A-test", "a", "s", json, t, Clock.systemUTC());
            assertThat(c.writeSession(() -> c.write(path.equals(CoupangProductClient.PATH) ? "POST" : "PUT", path, "", null)).status()).isEqualTo(200);
            verify(t, times(2)).send(anyString(), eq(path), anyString(), anyString(), nullable(byte[].class), any());
        }
    }
    @Test void definitePermanentRejectionsAndAmbiguousResponsesNeverRepeatWrites() {
        for (var r : List.of(response(200, OK), response(200, "{\"code\":\"ERROR\",\"message\":\"필수 값 누락\"}"),
                response(200, "not-json"), response(200, NOT_READY.replace("null", "123")),
                response(401, NOT_READY), response(403, NOT_READY), response(500, NOT_READY), response(408, NOT_READY))) {
            clearInvocations(transport); replies(r); var c = new CoupangProductClient("A", "a", "s", json, transport, Clock.systemUTC());
            c.writeSession(() -> c.write("PUT", approval, "", null)); calls(1);
        }
        for (var reason : List.of(TIMEOUT, NETWORK, RESPONSE)) {
            reset(transport); when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenThrow(new CoupangFailure(reason));
            var c = new CoupangProductClient("A", "a", "s", json, transport, Clock.systemUTC());
            assertThatThrownBy(() -> c.writeSession(() -> c.write("POST", CoupangProductClient.PATH, "", "{}"))).isInstanceOf(CoupangFailure.class); calls(1);
        }
    }
    @Test void readNetworkFailureRetriesButWriteLikePostReadRemainsReadOnly() {
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any()))
                .thenThrow(new CoupangFailure(NETWORK)).thenReturn(response(200, OK));
        assertThat(client.orderWithdrawalRead("{\"orderIds\":[\"PRIVATE_ORDER\"]}")).isEqualTo(OK.getBytes(StandardCharsets.UTF_8));
        calls(2); verify(transport, times(2)).send(eq("POST"), endsWith("/returnWithdrawList"), eq(""), anyString(), any(), any());
    }
    @Test void readProcessingAndServerErrorsShareRetryPolicyAndPreserveFinalFailure() {
        for (var r : List.of(response(400, PROCESSING), response(503, "{}"))) {
            reset(transport); replies(r, response(200, "{\"code\":\"SUCCESS\",\"data\":[],\"nextToken\":\"\"}"));
            var c = new CoupangProductClient("A", "a", "s", json, transport, Clock.systemUTC());
            assertThat(c.products(10, "").items()).isEmpty(); calls(2);
        }
        reset(transport); replies(response(503, "{}"));
        assertThatThrownBy(() -> client.products(10, "")).isInstanceOfSatisfying(CoupangFailure.class, e -> assertThat(e.kind()).isEqualTo(UPSTREAM)); calls(2);
    }
    @Test void aChangedGuardStopsTheSecondWrite() {
        replies(response(200, NOT_READY), response(200, OK));
        assertThatThrownBy(() -> client.writeSession(() -> client.write("PUT", approval, "", null,
                () -> { throw new InputValidationFailure("changed"); }))).isInstanceOf(InputValidationFailure.class); calls(1);
    }
    @Test void longRetryAfterDoesNotRetryAndBlocksLaterDispatch() {
        replies(new CoupangHttpTransport.Response(429, new byte[0], "3600"));
        client.writeSession(() -> client.write("PUT", approval, "", null));
        assertThatThrownBy(() -> client.writeSession(() -> client.write("PUT", approval, "", null))).isInstanceOf(CoupangFailure.class); calls(1);
    }
    @Test void cancellationDuringRetryDelayPreventsTheSecondRequest() throws Exception {
        String request = "10000000-1000-4000-8000-100000000099"; var first = new CountDownLatch(1);
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenAnswer(i -> { first.countDown(); return response(503, "{}"); });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> client.operation(1L, request, () -> client.products(10, "")));
            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue(); client.cancel(1L, request);
            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(CoupangFailure.class); calls(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void diagnosticsCorrelateAttemptsWithoutProviderOrRequestSecrets() {
        var logger = (Logger) LoggerFactory.getLogger(CoupangProductClient.class); var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try (var execution = MDC.putCloseable("coupangExecution", "42"); var step = MDC.putCloseable("coupangStep", "test-step")) {
            replies(response(200, NOT_READY), response(200, NOT_READY));
            client.writeSession(() -> client.write("PUT", approval, "PRIVATE_QUERY", "PRIVATE_BODY"));
            assertThat(appender.list).hasSize(4);
            assertThat(appender.list.stream().filter(e -> e.getFormattedMessage().contains("event=RESPONSE")))
                    .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
            assertThat(logs).contains("execution=42", "step=test-step", "operation=APPROVAL", "attempt=1", "attempt=2", "decision=RETRY", "decision=STOP")
                    .doesNotContain("PRIVATE", "임시저장", "/approvals", "sellerProductId", "A-test");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
