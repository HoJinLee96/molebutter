package cc.ataglace.molebutter.coupang.internal;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionTemplate;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway;

/** 접수된 실행의 임대·순차 호출·결과 기록을 담당한다. */
final class SubmissionWorker implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SubmissionWorker.class);
    QuickEditGuard quickGuard;
    private final BusinessAccess access;
    private final SubmissionStore store;
    private final CoupangWriteGateway writer;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final String owner = UUID.randomUUID().toString();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "coupang-submission");
        t.setDaemon(true);
        return t;
    });
    private volatile SubmissionStore.Job current;
    SubmissionWorker(BusinessAccess access, SubmissionStore store, CoupangWriteGateway writer,
            TransactionTemplate transactions, boolean enabled) {
        this.access = access;
        this.store = store;
        this.writer = writer;
        this.transactions = transactions;
        this.enabled = enabled;
    }
    void poll() {
        if (enabled && running.compareAndSet(false, true))
            executor.execute(() -> {
                try {
                    runPending();
                } finally {
                    running.set(false);
                }
            });
    }
    void heartbeat() {
        var job = current;
        if (job != null)
            store.heartbeat(job);
    }
    /**
     * One persisted step; integration tests can call this with a fake gateway and
     * disabled polling.
     */
    public void runPending() {
        transactions.executeWithoutResult(tx -> store.recoverExpired());
        var job = transactions.execute(tx -> store.claim(owner));
        if (job == null)
            return;
        current = job;
        CoupangWriteGateway.Result result;
        try (var executionContext = org.slf4j.MDC.putCloseable("coupangExecution", Long.toString(job.executionId()));
                var stepContext = org.slf4j.MDC.putCloseable("coupangStep", job.step().id())) {
            access.productActor(job.actor(), true);
            if (!job.prepared().supported())
                result = new CoupangWriteGateway.Result(CoupangWriteGateway.State.FAILED, job.mapping(),
                        "UNSUPPORTED_SNAPSHOT_VERSION", null, Instant.now());
            else if (!Objects.equals(writer.accountKey(), job.prepared().accountKey()))
                result = new CoupangWriteGateway.Result(CoupangWriteGateway.State.FAILED, job.mapping(),
                        "ACCOUNT_CHANGED", null, Instant.now());
            else if (!store.owns(job))
                return;
            else if (job.action().equals("RECONCILE")) {
                var previous = job.previous() == null
                        ? new CoupangWriteGateway.Result(CoupangWriteGateway.State.UNKNOWN, job.mapping(),
                                "INTERRUPTED", null, Instant.now())
                        : job.previous();
                result = writer.reconcile(job.actor(), job.prepared(), job.step(), previous);
            } else if (job.prepared().editIntent() == null)
                result = new CoupangWriteGateway.Result(CoupangWriteGateway.State.FAILED, job.mapping(),
                        "LEGACY_INTENT", null, Instant.now());
            else
                result = writer.execute(job.actor(), job.prepared(), job.step(), job.mapping(),
                        actual -> {
                            // Keep validation outside @Repository exception translation and before any dispatch.
                            if(quickGuard!=null)quickGuard.check(job.actor(),job.prepared().editIntent().quickEditBasis());
                            transactions.executeWithoutResult(tx -> {
                            access.productActor(job.actor(), true);
                            store.recordRequest(job, actual);
                        });});
            if (result == null)
                result = unknown(job, "RESPONSE");
            if (result.mapping() != null && (!job.prepared().accountKey().equals(result.mapping().accountKey())
                    || job.mapping() != null && job.mapping().sellerProductId() != null
                            && !Objects.equals(job.mapping().sellerProductId(), result.mapping().sellerProductId())))
                result = unknown(job, "MAPPING_CHANGED");
        } catch (BusinessException forbidden) {
            result = new CoupangWriteGateway.Result(CoupangWriteGateway.State.FAILED, job.mapping(), "ACCESS_DENIED",
                    null, Instant.now());
        } catch (RuntimeException unexpected) {
            log.warn("Coupang submission interrupted execution={} type={}", job.executionId(),
                    unexpected.getClass().getSimpleName());
            result = unknown(job, "INTERRUPTED");
        } finally {
            current = null;
        }
        final var outcome = result;
        try {
            transactions.executeWithoutResult(tx -> store.finish(job, outcome));
            // Never log upstream messages, request bodies, account keys or exception messages.
            if (outcome.state() == CoupangWriteGateway.State.FAILED || outcome.state() == CoupangWriteGateway.State.UNKNOWN)
                log.warn("[COUPANG_SUBMISSION] execution={} step={} type={} action={} state={} code={}",
                        job.executionId(), job.step().id(), job.step().type(), job.action(), outcome.state(),
                        SubmissionStore.publicCode(outcome.code()));
            else
                log.info("[COUPANG_SUBMISSION] execution={} step={} type={} action={} state={} code={}",
                        job.executionId(), job.step().id(), job.step().type(), job.action(), outcome.state(),
                        SubmissionStore.publicCode(outcome.code()));
        } catch (RuntimeException persistenceFailure) {
            log.warn("Coupang submission result commit failed execution={} step={} type={} state=UNKNOWN code=PERSISTENCE exception={}",
                    job.executionId(), job.step().id(), job.step().type(), persistenceFailure.getClass().getSimpleName());
            // The remote call has already happened. A failed result/mapping commit must
            // never make it retryable.
            transactions.executeWithoutResult(tx -> store.finish(job, unknown(job, "PERSISTENCE")));
        }
    }
    private static CoupangWriteGateway.Result unknown(SubmissionStore.Job job, String code) {
        return new CoupangWriteGateway.Result(CoupangWriteGateway.State.UNKNOWN, job.mapping(), code, null,
                Instant.now());
    }
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
