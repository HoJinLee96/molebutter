package cc.ataglace.molebutter.app.internal;

import java.time.Instant;
import java.util.List;

public record ProductImageUploadJob(String id, String status, String message, Instant startedAt,
        Instant finishedAt, Result result, String error) {
    public record File(String fileName, String key, String url) { }
    public record Result(String uploadProductCode, List<File> files, int totalCount) {
        public Result { files = List.copyOf(files); }
    }
    public boolean finished() { return !"RUNNING".equals(status); }
}
