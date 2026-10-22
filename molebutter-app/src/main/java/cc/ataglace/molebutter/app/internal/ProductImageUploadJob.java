package cc.ataglace.molebutter.app.internal;

import java.time.Instant;
import java.util.List;

public record ProductImageUploadJob(String id, String status, String message, Instant startedAt,
        Instant finishedAt, Result result, String error) {
    public record File(String fileName, String key, String url) { }
    public record SkippedFile(String fileName, String key, String url, String reason) { }
    public record Result(String uploadProductCode, List<File> files, int totalCount, List<SkippedFile> skippedFiles) {
        public Result { files = List.copyOf(files); skippedFiles = List.copyOf(skippedFiles); }
        public Result(String uploadProductCode, List<File> files, int totalCount) {
            this(uploadProductCode, files, totalCount, List.of());
        }
    }
    public boolean finished() { return !"RUNNING".equals(status); }
}
