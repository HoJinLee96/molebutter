package cc.ataglace.molebutter.imaging.internal.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.service.DownloadService.PreparedDownload;
import cc.ataglace.molebutter.imaging.internal.service.ImagingInputs;

/** Owned jobs and ZIP bytes. Running work and retained results both have fixed limits. */
@Component
public class JobRegistry {
    static final int MAX_JOBS = 32;
    static final int MAX_RUNNING_PER_ACTOR = 2;
    static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;
    private final Map<String, Entry> jobs = new LinkedHashMap<>();
    private final Executor executor;
    private final Duration retention;
    private final Clock clock;
    @Autowired
    public JobRegistry(@Qualifier("imagingDownloadJobExecutor") Executor executor,
            @Value("${imaging.job.retention:1h}") Duration retention) {
        this(executor, retention, Clock.systemUTC());
    }
    JobRegistry(Executor executor, Duration retention, Clock clock) {
        if (retention == null || retention.isNegative() || retention.isZero() || retention.compareTo(Duration.ofHours(4)) > 0) throw new IllegalArgumentException("Invalid imaging job retention");
        this.executor = executor; this.retention = retention; this.clock = clock;
    }
    public synchronized JobDto start(Long actor, Supplier<PreparedDownload> work) {
        ImagingInputs.actor(actor); cleanup();
        if (jobs.values().stream().filter(e -> actor.equals(e.actor()) && !e.job().finished()).count() >= MAX_RUNNING_PER_ACTOR) {
            throw new ImagingFailure(ImagingFailure.Kind.BUSY, "이미 진행 중인 다운로드가 있습니다. 완료 후 다시 시도해주세요.");
        }
        while (jobs.size() >= MAX_JOBS && evictOldestFinished()) { }
        if (jobs.size() >= MAX_JOBS) throw new ImagingFailure(ImagingFailure.Kind.BUSY, "다운로드 대기열이 가득 찼습니다.");
        String id = UUID.randomUUID().toString();
        JobDto snapshot = new JobDto(id, JobDto.STATUS_RUNNING, "상품 이미지 ZIP 작업을 시작했습니다.", clock.instant(), null, null, null);
        jobs.put(id, new Entry(actor, snapshot, null));
        try { executor.execute(() -> run(id, work)); }
        catch (RejectedExecutionException e) {
            jobs.remove(id);
            throw new ImagingFailure(ImagingFailure.Kind.BUSY, "다운로드 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요.");
        }
        return snapshot;
    }
    public synchronized JobDto get(Long actor, String id) { cleanup(); return owned(actor, id).job(); }
    public synchronized DownloadArtifact artifact(Long actor, String id) {
        cleanup(); Entry entry = owned(actor, id);
        if (!JobDto.STATUS_SUCCEEDED.equals(entry.job().status()) || entry.archive() == null) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "완료한 다운로드만 내려받을 수 있습니다.");
        }
        return new DownloadArtifact(entry.job().result().downloadName(), entry.archive());
    }
    private Entry owned(Long actor, String id) {
        ImagingInputs.actor(actor);
        Entry entry = jobs.get(id);
        if (entry == null || !Objects.equals(entry.actor(), actor)) {
            throw new ImagingFailure(ImagingFailure.Kind.NOT_FOUND, "다운로드 작업을 찾을 수 없습니다.");
        }
        return entry;
    }
    private void run(String id, Supplier<PreparedDownload> work) {
        try { succeeded(id, work.get()); }
        catch (Exception e) { failed(id, e); }
    }
    private synchronized void succeeded(String id, PreparedDownload result) {
        Entry current = jobs.get(id);
        if (current == null) return;
        if (result == null || result.archive() == null || result.archive().length > 32 * 1024 * 1024) {
            throw new ImagingFailure(ImagingFailure.Kind.INTERNAL, "다운로드 결과가 올바르지 않습니다.");
        }
        cleanup();
        while (artifactBytes() + result.archive().length > MAX_ARTIFACT_BYTES && evictOldestFinished()) { }
        JobDto job = new JobDto(id, JobDto.STATUS_SUCCEEDED, "상품 이미지 ZIP이 준비되었습니다.", current.job().startedAt(), clock.instant(), result.metadata(), null);
        jobs.put(id, new Entry(current.actor(), job, result.archive()));
    }
    private synchronized void failed(String id, Exception failure) {
        Entry current = jobs.get(id); if (current == null) return;
        String message = failure instanceof ImagingFailure e ? e.getMessage() : "이미지 ZIP을 만들지 못했습니다. 다시 시도해주세요.";
        JobDto job = new JobDto(id, JobDto.STATUS_FAILED, "다운로드에 실패했습니다.", current.job().startedAt(), clock.instant(), null, message);
        jobs.put(id, new Entry(current.actor(), job, null));
    }
    @Scheduled(fixedDelayString = "${imaging.cleanup-delay-ms:60000}")
    public synchronized void cleanup() {
        Instant cutoff = clock.instant().minus(retention);
        jobs.values().removeIf(e -> e.job().finished() && !e.job().finishedAt().isAfter(cutoff));
    }
    private boolean evictOldestFinished() {
        String id = jobs.entrySet().stream().filter(e -> e.getValue().job().finished()).map(Map.Entry::getKey).findFirst().orElse(null);
        return id != null && jobs.remove(id) != null;
    }
    private long artifactBytes() { return jobs.values().stream().filter(e -> e.archive() != null).mapToLong(e -> e.archive().length).sum(); }
    private record Entry(Long actor, JobDto job, byte[] archive) { }
}
