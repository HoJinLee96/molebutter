package cc.ataglace.molebutter.coupang.internal;

import java.time.Instant;
import java.util.List;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubmissionWorkerDiagnosticsTest {
    @Test void automaticApprovalLookupFailuresDoNotClaimThatTheProductWasRead() {
        assertThat(SubmissionStore.publicMessage("ACCEPTED","APPROVAL_LOOKUP_PROCESSING"))
                .contains("자동 결과 조회","쿠팡 상품 처리 중","다시 확인").doesNotContain("확인 완료");
        assertThat(SubmissionStore.publicMessage("ACCEPTED","APPROVAL_LOOKUP_FAILED"))
                .contains("자동 결과 조회를 완료하지 못했습니다","다시 확인").doesNotContain("확인 완료");
        assertThat(SubmissionStore.publicMessage("ACCEPTED","APPROVAL_PENDING"))
                .contains("등록 상품 확인 완료","판매 승인 대기");
        assertThat(SubmissionStore.publicMessage("ACCEPTED","APPROVAL_CHECK_TIMEOUT"))
                .contains("10분 동안 자동 결과 확인","완료되지 않았습니다").doesNotContain("확인 완료");
        assertThat(SubmissionStore.publicMessage("ACCEPTED","APPROVAL_CHECK_ACCESS_DENIED"))
                .contains("권한 변경","자동 결과 확인을 중단").doesNotContain("실패한 등록");
    }
    @Test void logsOnlyExecutionMetadataForRejectedAcceptedAndUnknownOutcomes() {
        var logger = (Logger) LoggerFactory.getLogger(SubmissionWorker.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            for (State state : List.of(State.FAILED, State.ACCEPTED, State.UNKNOWN)) {
                var access = mock(BusinessAccess.class); var store = mock(SubmissionStore.class);
                var writer = mock(CoupangWriteGateway.class); var manager = mock(PlatformTransactionManager.class);
                when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
                var mapping = new Mapping("PRIVATE_ACCOUNT", "123", List.of());
                var step = new Step("synthetic-step", Type.PRODUCT, null, "PUT", "/synthetic", "", "PRIVATE_REQUEST", "{}", "{}");
                var prepared = new Prepared(mapping.accountKey(), mapping, List.of(step), List.of(), Instant.now(),
                        List.of(), new EditIntent(null, List.of()), 4);
                var job = new SubmissionStore.Job(42, 43, 1, 1L, "owner", prepared, step, "WRITE", mapping, null, 44);
                when(store.claim(anyString())).thenReturn(job); when(store.owns(job)).thenReturn(true);
                when(writer.accountKey()).thenReturn(mapping.accountKey());
                var result = new Result(state, mapping, "HTTP_200_REJECTED", "PRIVATE_RAW_MESSAGE", Instant.now(),
                        "PRIVATE_REQUEST", "PRIVATE_DIAGNOSTIC");
                when(writer.execute(anyLong(), any(), any(), any(), any())).thenAnswer(i -> {
                    assertThat(org.slf4j.MDC.get("coupangExecution")).isEqualTo("42");
                    assertThat(org.slf4j.MDC.get("coupangStep")).isEqualTo(step.id());
                    return result;
                });
                try (var worker = new SubmissionWorker(access, store, writer, new TransactionTemplate(manager), false)) {
                    worker.runPending();
                }
                assertThat(org.slf4j.MDC.get("coupangExecution")).isNull();
                assertThat(org.slf4j.MDC.get("coupangStep")).isNull();
                verify(store).finish(job, result);
                var event = appender.list.getLast();
                assertThat(event.getFormattedMessage()).contains("execution=42", "step=synthetic-step", "type=PRODUCT",
                        "action=WRITE", "state=" + state, "code=HTTP_200_REJECTED").doesNotContain("PRIVATE_");
                assertThat(event.getLevel()).isEqualTo(state == State.ACCEPTED ? Level.INFO : Level.WARN);
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
