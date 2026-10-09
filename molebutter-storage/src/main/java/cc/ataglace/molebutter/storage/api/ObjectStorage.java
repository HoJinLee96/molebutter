package cc.ataglace.molebutter.storage.api;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded object storage; no bucket administration or authorization is performed here.
 * Keys are relative UTF-8 paths without empty, dot or parent segments.
 * A WRITE_UNCERTAIN result requires reading the current state before deciding what to do next.
 */
public interface ObjectStorage {
    /** Creates only when the key does not already exist. */
    ObjectMetadata create(StorageArea area, String key, byte[] content, String contentType, Map<String, String> metadata);

    /** Replaces only when the opaque ETag returned by get/head/create still matches. */
    ObjectMetadata replace(StorageArea area, String key, String expectedETag, byte[] content,
                           String contentType, Map<String, String> metadata);

    Optional<StoredObject> get(StorageArea area, String key);

    Optional<ObjectMetadata> head(StorageArea area, String key);

    /** Deletes one exact key; a missing key is a successful no-op. */
    void delete(StorageArea area, String key);

    /** One bounded request, limit 1..1000. Null token starts a listing; tokens must remain opaque. */
    ObjectPage list(StorageArea area, String prefix, String continuationToken, int limit);

    /** Private objects never have a public URL. This does not check object existence. */
    Optional<URI> publicUrl(StorageArea area, String key);
}
