package cc.ataglace.molebutter.storage.api;

import java.time.Instant;
import java.util.Map;

/**
 * ETag is opaque, including any quotes. lastModified is absent (null) for writes;
 * list entries omit contentType (null) and user metadata. Use head for full details.
 */
public record ObjectMetadata(StorageArea area, String key, long size, String contentType, String eTag,
                             Instant lastModified, Map<String, String> metadata) {
    public ObjectMetadata {
        metadata = Map.copyOf(metadata);
    }
}
