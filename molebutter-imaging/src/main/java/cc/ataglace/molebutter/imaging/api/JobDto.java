package cc.ataglace.molebutter.imaging.api;
import java.time.Instant;
/** In-memory job snapshot: RUNNING | SUCCEEDED | FAILED. */
public record JobDto(String id, String status, String message, Instant startedAt,
        Instant finishedAt, DownloadResultDto result, String errorMessage) {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public boolean finished() { return !STATUS_RUNNING.equals(status); }
}
