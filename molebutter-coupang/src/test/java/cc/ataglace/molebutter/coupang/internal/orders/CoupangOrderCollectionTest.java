package cc.ataglace.molebutter.coupang.internal.orders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangOrderCollections.*;
import cc.ataglace.molebutter.coupang.api.CoupangOrders.*;

class CoupangOrderCollectionTest {
    final BusinessAccess access = mock(BusinessAccess.class);
    final CoupangOrderStore store = mock(CoupangOrderStore.class);
    final CoupangOrderGateway client = mock(CoupangOrderGateway.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final Request request = new Request("2026-10-01", "2026-10-07", "00000000-0000-0000-0000-000000000001");
    final CoupangOrderStore.StoredJob job = new CoupangOrderStore.StoredJob(12L, 3L, "account", "RUNNING", request,
            "2026-10-07T00:00:00Z", null);
    final CoupangOrderStore.Checkpoint checkpoint = new CoupangOrderStore.Checkpoint(22L, 12L, "ACCEPT",
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-07"), "before", "PENDING", null);
    DefaultCoupangOrderCollections service() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(client.accountKey()).thenReturn("account");
        when(client.configured()).thenReturn(true);
        when(client.orderStream(anyString())).thenAnswer(
                i -> List.of("ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING")
                        .contains(i.getArgument(0)));
        when(store.progress(anyLong())).thenReturn(Progress.empty());
        return new DefaultCoupangOrderCollections(access, store, client, manager, Clock.systemUTC());
    }
    @Test
    void disabledWorkerDoesNotClaimOrFetchJobs() {
        new DefaultCoupangOrderCollections(access, store, client, manager, false).collect();
        verifyNoInteractions(access, store, client, manager);
    }
    OrderRow row(String box) {
        return new OrderRow("123", box, "1", "987", "456", "SKU", "상품", "옵션", "ACCEPT", 1L, 0L, 0L,
                new Money("KRW", "100"), new Money("KRW", "100"), "2026-10-01T00:00:00+09:00",
                "2026-10-01T00:00:00+09:00", null, null, List.of());
    }
    @Test
    void defaultsUseKstCalendarAndInclusiveSevenDays() {
        var r = DefaultCoupangOrderCollections.normalize(new Request(null, null),
                Clock.fixed(Instant.parse("2026-10-06T16:00:00Z"), ZoneOffset.UTC));
        assertThat(r.dateTo()).isEqualTo("2026-10-07");
        assertThat(r.dateFrom()).isEqualTo("2026-10-01");
        assertThat(r.requestId()).isNotBlank();
    }
    @Test
    void thirtyOneDaysAllowedButThirtyTwoAndFutureRejected() {
        var clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        assertThat(DefaultCoupangOrderCollections.normalize(new Request("2026-09-07", "2026-10-07"), clock).dateFrom())
                .isEqualTo("2026-09-07");
        for (var r : List.of(new Request("2026-09-06", "2026-10-07"), new Request("2026-10-07", "2026-10-08")))
            assertThatThrownBy(() -> DefaultCoupangOrderCollections.normalize(r, clock))
                    .isInstanceOf(InputValidationFailure.class);
    }
    @Test
    void incompleteSnapshotCannotReplaceChildrenAndDuplicateIdentitiesRejected() {
        var valid = new CoupangOrderGateway.Snapshot("123", List.of(row("1"), row("2")), true);
        assertThatCode(() -> CoupangOrderStore.validateSnapshot(valid)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CoupangOrderStore
                .validateSnapshot(new CoupangOrderGateway.Snapshot("123", List.of(row("1")), false)))
                .isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(() -> CoupangOrderStore
                .validateSnapshot(new CoupangOrderGateway.Snapshot("123", List.of(row("1"), row("1")), true)))
                .isInstanceOf(InputValidationFailure.class);
    }
    @Test
    void cancelledOrLostLeaseDiscardsFetchedPage() {
        var service = service();
        when(store.find(12, true)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(false);
        service.commitPage(job, checkpoint,
                new CoupangOrderGateway.Page(List.of(new CoupangOrderGateway.Snapshot("123", List.of(row("1")), true)),
                        List.of(), null, true, null));
        verify(store, never()).saveSnapshot(anyString(), any(), anyLong());
        verify(store, never()).checkpoint(anyLong(), any(), anyString(), any());
    }
    @Test
    void partialPageRetainsOriginalCursorForResume() {
        var service = service();
        when(store.find(12, true)).thenReturn(job);
        when(store.find(12, false)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        when(store.checkpoints(12)).thenReturn(List.of(checkpoint));
        service.commitPage(job, checkpoint,
                new CoupangOrderGateway.Page(List.of(new CoupangOrderGateway.Snapshot("123", List.of(row("1")), true)),
                        List.of(), "after", false, null));
        verify(store).saveSnapshot(eq("account"), any(), eq(12L));
        verify(store).checkpoint(22, "before", "PARTIAL", "일부 조회 계약 또는 주문 상세를 확인하지 못했습니다.");
        verify(store, never()).rememberCursor(anyLong(), any());
    }
    @Test
    void cursorCycleIsRejectedBeforeCheckpointAdvancement() {
        var service = service();
        when(store.find(12, true)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        when(store.cursorSeen(22, "older")).thenReturn(true);
        assertThatThrownBy(() -> service.commitPage(job, checkpoint,
                new CoupangOrderGateway.Page(List.of(), List.of(), "older", true, null)))
                .isInstanceOf(CoupangFailure.class);
        verify(store, never()).checkpoint(anyLong(), any(), anyString(), any());
    }
    @Test
    void databaseLeasePreventsSecondWorkerFromCallingRemote() {
        var service = service();
        when(store.claimJob(anyString(), anyString())).thenReturn(null);
        service.collect();
        verify(client, never()).fetch(anyString(), any(), any(), any());
        verify(client, never()).snapshot(anyString());
    }
    @Test
    void currentAdministratorIsRequiredBeforeReadingJob() {
        var service = service();
        doThrow(new InputValidationFailure("denied")).when(access).productActor(3L, true);
        assertThatThrownBy(() -> service.get(3L, "12")).isInstanceOf(InputValidationFailure.class);
        verify(store, never()).find(anyLong(), anyBoolean());
    }
    @Test
    void liveDetailRechecksAdministratorAfterRemoteResponse() {
        var orders = new DefaultCoupangOrders(access, store, client);
        when(client.detail("123")).thenReturn(new Detail("123", List.of(), List.of()));
        orders.detail(3L, "123");
        verify(access, times(2)).productActor(3L, true);
        verify(store, never()).saveSnapshot(anyString(), any(), anyLong());
    }
    @Test
    void knownClaimMissingAtLastPageIsPartial() {
        var service = service();
        var known = new CoupangOrderStore.Checkpoint(22L, 12L, "CLAIMEXCHANGE:123:999", checkpoint.from(),
                checkpoint.to(), "before", "PENDING", null);
        when(store.find(12, true)).thenReturn(job);
        when(store.find(12, false)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        when(store.checkpoints(12)).thenReturn(List.of(known));
        service.commitPage(job, known, new CoupangOrderGateway.Page(List.of(), List.of(), null, true, null));
        verify(store).checkpoint(22, "before", "PARTIAL", "일부 조회 계약 또는 주문 상세를 확인하지 못했습니다.");
        verify(store, never()).rememberCursor(anyLong(), any());
    }
    @Test
    void timestampsNormalizeOffsetsToKstForFilterBoundaries() {
        assertThat(CoupangOrderStore.kstTimestamp("2026-10-06T15:00:00Z").toLocalDateTime())
                .isEqualTo(LocalDateTime.parse("2026-10-07T00:00:00"));
        assertThat(CoupangOrderStore.kstTimestamp("2026-10-07T00:00:00+09:00").toLocalDateTime())
                .isEqualTo(LocalDateTime.parse("2026-10-07T00:00:00"));
        assertThat(CoupangOrderStore.kstTimestamp("2026-10-07T00:00:00").toLocalDateTime())
                .isEqualTo(LocalDateTime.parse("2026-10-07T00:00:00"));
        assertThat(CoupangOrderStore.kstTimestamp("unknown")).isNull();
    }
    @Test
    void administratorRevokedDuringRemoteReadCannotCommit() {
        var service = service();
        when(store.find(12, true)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        doThrow(new InputValidationFailure("denied")).when(access).productActor(3L, true);
        assertThatThrownBy(() -> service.commitPage(job, checkpoint,
                new CoupangOrderGateway.Page(List.of(new CoupangOrderGateway.Snapshot("123", List.of(row("1")), true)),
                        List.of(), null, true, null)))
                .isInstanceOf(InputValidationFailure.class);
        verify(store, never()).saveSnapshot(anyString(), any(), anyLong());
    }

    @Test
    void verifiedGateRetryAddsMissingReturnAndCancelStreamsWithoutRepeatingCompletedOnes() {
        var service = service();
        var partial = new CoupangOrderStore.StoredJob(12L, 3L, "account", "PARTIAL", request, job.startedAt(),
                job.finishedAt());
        var gate = new CoupangOrderStore.Checkpoint(23L, 12L, "CLAIMS_UNVERIFIED", checkpoint.from(), checkpoint.to(),
                null, "PARTIAL", "unverified");
        var completed = new CoupangOrderStore.Checkpoint(24L, 12L, "RETURN_RU", checkpoint.from(), checkpoint.to(),
                null, "DONE", null);
        when(store.find(12, true)).thenReturn(partial);
        when(store.find(12, false)).thenReturn(partial);
        when(store.checkpoints(12)).thenReturn(List.of(gate, completed));
        when(client.claimsVerified()).thenReturn(true);
        when(client.streams())
                .thenReturn(List.of("ACCEPT", "RETURN_RU", "RETURN_UC", "CANCEL", "EXCHANGE", "WITHDRAWN"));
        service.retry(3L, "12");
        verify(store).addCheckpoint(12, "RETURN_UC", checkpoint.from(), checkpoint.to());
        verify(store).addCheckpoint(12, "CANCEL", checkpoint.from(), checkpoint.to());
        verify(store, never()).addCheckpoint(12, "RETURN_RU", checkpoint.from(), checkpoint.to());
        verify(store).checkpoint(23, null, "DONE", null);
        verify(store).retry(12);
    }
    @Test
    void confirmedWithdrawalResolvesFailedKnownReceiptRefresh() {
        var service = service();
        var failed = new CoupangOrderStore.Checkpoint(23L, 12L, "CLAIMRETURN:999", checkpoint.from(), checkpoint.to(),
                null, "FAILED", "rejected");
        when(store.find(12, true)).thenReturn(job);
        when(store.find(12, false)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        when(store.checkpoints(12)).thenReturn(List.of(failed));
        when(store.withdrawnReceipt("account", "999")).thenReturn(true);
        service.commitPage(job, checkpoint, new CoupangOrderGateway.Page(List.of(), List.of(), null, true, null));
        verify(store).markExpectedSeen(23);
        verify(store).checkpoint(23, null, "DONE", null);
    }

    @Test
    void detailFailurePreservesSuccessfulPageCursorAndOnlyPersistsIndependentTask() {
        var service = service();
        when(store.find(12, true)).thenReturn(job);
        when(store.find(12, false)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        when(store.checkpoints(12)).thenReturn(List.of(checkpoint));
        service.commitPage(job, checkpoint, new CoupangOrderGateway.Page(List.of(), List.of(), "after", true, null),
                List.of(new DefaultCoupangOrderCollections.DetailFailure("123", "TIMEOUT", "응답 시간이 초과되었습니다.")));
        verify(store).failedDetail(12, "123", checkpoint, "TIMEOUT", "응답 시간이 초과되었습니다.");
        verify(store).checkpoint(22, "after", "PENDING", null);
        verify(store).rememberCursor(22, "before");
        verify(store).pageCompleted(22);
    }
    @Test
    void retryWorkerOnlyFetchesFailedDetailWithoutReplayingSuccessfulPage() {
        var service = service();
        var task = new CoupangOrderStore.FailedDetail(33, 12, "123", "ACCEPT", checkpoint.from(), checkpoint.to(),
                "PENDING", "TIMEOUT", "timeout");
        when(store.claimJob(anyString(), eq("account"))).thenReturn(12L);
        when(store.find(12, false)).thenReturn(job);
        when(store.find(12, true)).thenReturn(job);
        when(store.details(12)).thenReturn(List.of(task));
        when(store.renew(anyLong(), anyString())).thenReturn(true);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        when(client.snapshot("123")).thenReturn(new CoupangOrderGateway.Snapshot("123", List.of(row("1")), true));
        when(store.checkpoints(12)).thenReturn(List.of());
        service.collect();
        verify(client).snapshot("123");
        verify(client, never()).fetch(anyString(), any(), any(), any());
        verify(store).saveSnapshot(eq("account"), any(), eq(12L));
        verify(store).detailDone(12, "123");
    }
    @Test
    void mutationLocksAccountBeforeJobToKeepLeaseAndCancellationAtomic() {
        var service = service();
        when(store.find(12, true)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(false);
        service.commitPage(job, checkpoint, new CoupangOrderGateway.Page(List.of(), List.of(), null, true, null));
        var sequence = inOrder(store);
        sequence.verify(store).lockAccount("account");
        sequence.verify(store).find(12, true);
    }
    @Test
    void claimStateRejectsOlderAndUnknownTimestamps() {
        var known = new CoupangOrderStore.ClaimVersion(CoupangOrderStore.kstTimestamp("2026-10-07T10:00:00"),
                "RETURNS_COMPLETED");
        assertThat(CoupangOrderStore.acceptClaim(List.of(known), CoupangOrderStore.kstTimestamp("2026-10-06T10:00:00"),
                "RETURNS_UNCHECKED")).isFalse();
        assertThat(CoupangOrderStore.acceptClaim(List.of(known), null, "RETURNS_UNCHECKED")).isFalse();
        assertThat(CoupangOrderStore.acceptClaim(List.of(known), CoupangOrderStore.kstTimestamp("2026-10-08T10:00:00"),
                "WITHDRAWN")).isTrue();
    }
    @Test
    void equalTimeConflictingClaimsRemainUnverifiedAndDoNotReplaceSnapshot() {
        var known = new CoupangOrderStore.ClaimVersion(CoupangOrderStore.kstTimestamp("2026-10-07T10:00:00"),
                "RETURNS_UNCHECKED");
        assertThat(CoupangOrderStore.acceptClaim(List.of(known), known.updatedAt(), "WITHDRAWN")).isFalse();
        assertThat(CoupangOrderStore.acceptClaim(List.of(known), known.updatedAt(), known.status())).isTrue();
    }
    @Test
    void missingTimestampTerminalIsNeverMarkedVerifiedByPublicProjection() {
        var claim = new ClaimSummary("RETURN", "1", "WITHDRAWN", null, null, false);
        assertThat(claim.latestVerified()).isFalse();
        assertThat(CoupangOrderStore.acceptClaim(List.of(new CoupangOrderStore.ClaimVersion(null, "WITHDRAWN")),
                CoupangOrderStore.kstTimestamp("2026-10-07T10:00:00"), "RETURNS_UNCHECKED")).isFalse();
    }
    @Test
    void spaceSeparatedApiDatesAndIsoDatesUseIdenticalKstInstant() {
        assertThat(CoupangOrderStore.kstTimestamp("2026-10-07 10:20:30"))
                .isEqualTo(CoupangOrderStore.kstTimestamp("2026-10-07T10:20:30"));
    }

    @Test
    void sellerMismatchStopsTheJobDuringPageRead() {
        var service = service();
        when(store.claimJob(anyString(), eq("account"))).thenReturn(12L);
        when(store.find(12, false)).thenReturn(job);
        when(store.find(12, true)).thenReturn(job);
        when(store.isOwned(anyLong(), anyString())).thenReturn(true);
        when(store.checkpoints(12)).thenReturn(List.of(checkpoint));
        when(client.fetch(anyString(), any(), any(), any()))
                .thenThrow(new CoupangFailure(CoupangFailure.Kind.ORDER_ACCOUNT_MISMATCH));
        service.collect();
        verify(store).status(12, "FAILED");
        verify(store).jobError(eq(12L), eq("ORDER_PAGE"), eq("ORDER_ACCOUNT_MISMATCH"), anyString());
        verify(client, never()).snapshot(anyString());
    }
}
