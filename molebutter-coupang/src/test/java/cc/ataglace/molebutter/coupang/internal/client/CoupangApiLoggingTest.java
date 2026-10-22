package cc.ataglace.molebutter.coupang.internal.client;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.CoupangFailure;
import static cc.ataglace.molebutter.coupang.api.CoupangFailure.Kind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoupangApiLoggingTest {
    final Logger logger = (Logger) LoggerFactory.getLogger(CoupangProductClient.class);
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final CoupangHttpTransport transport = mock(CoupangHttpTransport.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC);
    final String success = "{\"code\":\"SUCCESS\",\"data\":123}";
    CoupangProductClient client() {
        return new CoupangProductClient("PRIVATE_VENDOR", "PRIVATE_ACCESS", "PRIVATE_SECRET", new ObjectMapper(), transport, clock);
    }
    @BeforeEach void capture() { logs.start(); logger.addAppender(logs); }
    @AfterEach void release() { logger.detachAppender(logs); logs.stop(); }
    CoupangHttpTransport.Response response(int status, String body) {
        return new CoupangHttpTransport.Response(status, body.getBytes(StandardCharsets.UTF_8));
    }
    void reply(int status, String body) {
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any()))
                .thenReturn(response(status, body));
    }
    Map<String,String> fields(ILoggingEvent event) {
        var fields = new HashMap<String,String>();
        for (String part : event.getFormattedMessage().split(" ")) {
            int equals = part.indexOf('=');
            if (equals > 0) fields.put(part.substring(0, equals), part.substring(equals + 1));
        }
        return fields;
    }
    void safeLogs() {
        String text = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(text).doesNotContain("PRIVATE", "Authorization", "Cookie", "query-secret", "body-secret", "/vendors/", "/seller-products", "임시저장");
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    @Test void successHasPairedInfoEventsAndMonotonicResponseGapAcrossSessions() {
        var starts = new ArrayList<Long>(); var finishes = new ArrayList<Long>();
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any())).thenAnswer(i -> {
            starts.add(System.nanoTime());
            if (starts.size() == 1) Thread.sleep(1100);
            finishes.add(System.nanoTime()); return response(200, success);
        });
        var c = client();
        c.orderRead(CoupangProductClient.PATH, "query-secret");
        c.writeSession(() -> c.write("PUT", "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/222/prices/1000", "", "body-secret"));
        assertThat(logs.list).hasSize(4).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        var firstStart = fields(logs.list.get(0)); var firstEnd = fields(logs.list.get(1));
        var nextStart = fields(logs.list.get(2)); var nextEnd = fields(logs.list.get(3));
        assertThat(firstStart).containsEntry("event", "START").containsEntry("previousResponseAt", "-")
                .containsEntry("sincePreviousResponseMs", "-").containsEntry("previousFinishedAt", "-");
        assertThat(firstStart.get("startedAt")).isEqualTo("2026-10-21T17:00:00+09:00");
        assertThat(firstEnd).containsEntry("event", "RESPONSE").containsEntry("http", "200").containsEntry("code", "SUCCESS");
        assertThat(firstEnd.get("request")).isEqualTo(firstStart.get("request"));
        assertThat(Long.parseLong(firstEnd.get("durationMs"))).isGreaterThanOrEqualTo(1100);
        assertThat(nextStart).containsEntry("operation", "PRICE").containsEntry("method", "PUT")
                .containsEntry("previousResponseAt", firstEnd.get("responseAt"));
        long gap = Long.parseLong(nextStart.get("sincePreviousResponseMs"));
        assertThat(gap).isGreaterThanOrEqualTo(2000);
        assertThat(Long.parseLong(nextStart.get("paceWaitMs"))).isBetween(0L, gap);
        assertThat(starts.get(1) - finishes.get(0)).isGreaterThanOrEqualTo(2_000_000_000L);
        assertThat(nextEnd.get("request")).isEqualTo(nextStart.get("request")).isNotEqualTo(firstStart.get("request"));
        safeLogs();
    }

    @Test void rejectsAreWarnAndUnrecoverableTransportOrResponseProblemsAreError() {
        record Case(int http, String body, Level level, String code) {}
        for (var scenario : List.of(
                new Case(200, success, Level.INFO, "SUCCESS"),
                new Case(200, "{\"code\":\"ERROR\",\"message\":\"PRIVATE_PROVIDER\"}", Level.WARN, "REJECTED"),
                new Case(401, "PRIVATE_PROVIDER", Level.WARN, "AUTHENTICATION"),
                new Case(403, "PRIVATE_PROVIDER", Level.WARN, "PERMISSION"),
                new Case(500, "PRIVATE_PROVIDER", Level.ERROR, "UPSTREAM"),
                new Case(200, "PRIVATE_BROKEN_JSON", Level.ERROR, "INVALID_RESPONSE"))) {
            logs.list.clear(); reply(scenario.http(), scenario.body()); var c = client();
            c.writeSession(() -> c.write("POST", CoupangProductClient.PATH, "query-secret", "body-secret"));
            assertThat(logs.list).hasSize(2);
            assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(logs.list.get(1).getLevel()).isEqualTo(scenario.level());
            assertThat(fields(logs.list.get(1))).containsEntry("event", "RESPONSE").containsEntry("code", scenario.code())
                    .containsEntry("http", Integer.toString(scenario.http())).containsEntry("decision", "STOP");
            safeLogs();
        }
    }

    @Test void everyRetryHasItsOwnStartAndEndWithOneRequestIdAndRecoveriesAreInfo() {
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any()))
                .thenReturn(new CoupangHttpTransport.Response(429, new byte[0], "0"), response(200, success));
        try (var execution = MDC.putCloseable("coupangExecution", "42"); var step = MDC.putCloseable("coupangStep", "test-step")) {
            var c = client(); c.writeSession(() -> c.write("PUT", CoupangProductClient.PATH + "/123/approvals", "query-secret", null));
            assertThat(logs.list).hasSize(4);
            String request = fields(logs.list.getFirst()).get("request");
            assertThat(logs.list).allSatisfy(e -> assertThat(fields(e)).containsEntry("request", request)
                    .containsEntry("execution", "42").containsEntry("step", "test-step"));
            assertThat(logs.list.get(1).getLevel()).isEqualTo(Level.WARN);
            assertThat(fields(logs.list.get(1))).containsEntry("attempt", "1").containsEntry("decision", "RETRY");
            assertThat(logs.list.get(3).getLevel()).isEqualTo(Level.INFO);
            assertThat(fields(logs.list.get(3))).containsEntry("attempt", "2").containsEntry("code", "SUCCESS");
            assertThat(Long.parseLong(fields(logs.list.get(2)).get("sincePreviousResponseMs"))).isGreaterThanOrEqualTo(2000);
            safeLogs();
        }
    }

    @Test void networkFailureDoesNotInventAResponseAndExhaustedReadIsError() {
        when(transport.send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any()))
                .thenThrow(new CoupangFailure(NETWORK));
        var c = client();
        assertThatThrownBy(() -> c.orderRead("/v2/providers/openapi/apis/api/v5/vendors/PRIVATE_VENDOR/ordersheets", "query-secret"))
                .isInstanceOf(CoupangFailure.class);
        assertThat(logs.list).hasSize(4);
        assertThat(logs.list.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(3).getLevel()).isEqualTo(Level.ERROR);
        for (int n : List.of(1, 3)) assertThat(fields(logs.list.get(n)))
                .containsEntry("event", "FAILURE").containsEntry("responseAt", "-")
                .containsEntry("http", "0").containsEntry("code", "NETWORK");
        var retry = fields(logs.list.get(2));
        assertThat(retry).containsEntry("previousResponseAt", "-").containsEntry("sincePreviousResponseMs", "-");
        assertThat(Long.parseLong(retry.get("sincePreviousFinishMs"))).isGreaterThanOrEqualTo(2000);
        safeLogs();
    }

    @Test void knownReadEnvelopesAreSuccessButUnknownBodiesRemainErrors() {
        record Case(String path, String body, String code) {}
        String outbound = "/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound";
        String vendors = "/v2/providers/openapi/apis/api/v5/vendors/PRIVATE_VENDOR/";
        for (var scenario : List.of(
                new Case(outbound, "{\"content\":[],\"pagination\":{}}", "SUCCESS"),
                new Case(vendors + "returnShippingCenters", "{\"code\":200,\"data\":{\"content\":[],\"pagination\":{}}}", "SUCCESS"),
                new Case(vendors + "ordersheets", "{\"code\":200,\"data\":[]}", "SUCCESS"),
                new Case(vendors + "returnWithdrawRequests", "{\"code\":\"200\",\"data\":[]}", "SUCCESS"),
                new Case("/v2/providers/openapi/apis/api/v1/categorization/predict", "{\"code\":200,\"data\":{\"autoCategorizationPredictionResultType\":\"FAILURE\"}}", "SUCCESS"),
                new Case(CoupangProductClient.PATH, "{\"code\":200,\"data\":{\"code\":\"SUCCESS\",\"data\":123}}", "SUCCESS"),
                new Case(CoupangProductClient.PATH, "{\"code\":200,\"data\":{\"PRIVATE_UNKNOWN\":true}}", "INVALID_RESPONSE"))) {
            logs.list.clear(); reply(200, scenario.body()); client().orderRead(scenario.path(), "query-secret");
            assertThat(logs.list).hasSize(2);
            assertThat(fields(logs.list.get(1))).containsEntry("code", scenario.code());
            assertThat(logs.list.get(1).getLevel()).isEqualTo(scenario.code().equals("SUCCESS") ? Level.INFO : Level.ERROR);
            safeLogs();
        }
    }

    @Test void unexpectedExceptionsAndCancellationHaveFixedSafeOutcomesAndAreNotRetried() {
        for (var error : List.of(new IllegalStateException("PRIVATE_EXCEPTION", new RuntimeException("PRIVATE_CAUSE")), new CoupangFailure(CANCELLED))) {
            logs.list.clear(); clearInvocations(transport);
            doThrow(error).when(transport).send(anyString(), anyString(), anyString(), anyString(), nullable(byte[].class), any());
            var c = client();
            assertThatThrownBy(() -> c.writeSession(() -> c.write("POST", CoupangProductClient.PATH, "query-secret", "body-secret"))).isSameAs(error);
            assertThat(logs.list).hasSize(2);
            var end = logs.list.get(1);
            assertThat(fields(end)).containsEntry("event", "FAILURE").containsEntry("responseAt", "-")
                    .containsEntry("code", error instanceof CoupangFailure ? "CANCELLED" : "CLIENT_ERROR");
            assertThat(end.getLevel()).isEqualTo(error instanceof CoupangFailure ? Level.WARN : Level.ERROR);
            verify(transport, times(1)).send(anyString(), anyString(), anyString(), anyString(), any(), any());
            safeLogs();
        }
    }
    @Test void scopedFiveSecondGapOnlyAffectsFirstAttemptAndDoesNotLeakAfterRetryOrFailure() {
        var starts=new ArrayList<Long>();var finishes=new ArrayList<Long>();var approvalAttempts=new java.util.concurrent.atomic.AtomicInteger();
        when(transport.send(anyString(),anyString(),anyString(),anyString(),nullable(byte[].class),any())).thenAnswer(call->{
            starts.add(System.nanoTime());
            boolean rejected=call.<String>getArgument(1).endsWith("/approvals")&&approvalAttempts.incrementAndGet()==1;
            var response=response(200,rejected?CoupangRetryTest.NOT_READY:success);
            finishes.add(System.nanoTime());return response;
        });
        var c=client();
        c.writeSession(()->c.write("POST",CoupangProductClient.PATH,"",null));
        c.writeSession(()->c.withFirstResponseGap(Duration.ofSeconds(5),()->c.write("PUT",CoupangProductClient.PATH+"/123/approvals","",null)));
        c.orderRead("/v2/providers/test/read","");
        assertThatThrownBy(()->c.withFirstResponseGap(Duration.ofSeconds(5),()->{throw new IllegalArgumentException("synthetic");})).isInstanceOf(IllegalArgumentException.class);
        c.orderRead("/v2/providers/test/read","");
        assertThat(starts).hasSize(5);
        assertThat(starts.get(1)-finishes.get(0)).isGreaterThanOrEqualTo(5_000_000_000L);
        for(int i=2;i<starts.size();i++)assertThat(starts.get(i)-finishes.get(i-1)).isBetween(2_000_000_000L,4_900_000_000L);
        var startEvents=logs.list.stream().map(this::fields).filter(f->"START".equals(f.get("event"))).toList();
        assertThat(Long.parseLong(startEvents.get(1).get("sincePreviousResponseMs"))).isGreaterThanOrEqualTo(5000);
        assertThat(Long.parseLong(startEvents.get(1).get("paceWaitMs"))).isGreaterThanOrEqualTo(4900);
        assertThat(startEvents.get(2)).containsEntry("attempt","2");
        assertThat(Long.parseLong(startEvents.get(2).get("sincePreviousResponseMs"))).isBetween(2000L,4900L);
        safeLogs();
    }

}
