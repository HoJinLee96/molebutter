package cc.ataglace.molebutter.media.api;

import java.time.Instant;
import java.util.List;

/** Read-only product image library. No browser-supplied metadata is trusted. */
public interface R2Images {
    Page list(Long actor, String productCode, String cursor);
    Image resolve(Long actor, String url);
    Content read(Long actor, String url);
    void validateDescription(Long actor, String html, String previousHtml);
    record Entry(String key, String url, String folder, long bytes, Instant lastModified) {}
    record Page(String productCode, List<Entry> images, String nextCursor, List<String> folders) {}
    record Image(String key, String url, String mimeType, int width, int height, long bytes) {}
    record Content(Image image, byte[] bytes) {}
}
