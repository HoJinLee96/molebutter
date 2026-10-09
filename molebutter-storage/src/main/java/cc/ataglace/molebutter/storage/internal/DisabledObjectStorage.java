package cc.ataglace.molebutter.storage.internal;

import cc.ataglace.molebutter.storage.api.*;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

final class DisabledObjectStorage implements ObjectStorage {
    private StorageException disabled() { return new StorageException(StorageException.Code.NOT_CONFIGURED); }

    @Override public ObjectMetadata create(StorageArea area, String key, byte[] content, String contentType, Map<String, String> metadata) { throw disabled(); }
    @Override public ObjectMetadata replace(StorageArea area, String key, String expectedETag, byte[] content, String contentType, Map<String, String> metadata) { throw disabled(); }
    @Override public Optional<StoredObject> get(StorageArea area, String key) { throw disabled(); }
    @Override public Optional<ObjectMetadata> head(StorageArea area, String key) { throw disabled(); }
    @Override public void delete(StorageArea area, String key) { throw disabled(); }
    @Override public ObjectPage list(StorageArea area, String prefix, String continuationToken, int limit) { throw disabled(); }
    @Override public Optional<URI> publicUrl(StorageArea area, String key) { throw disabled(); }
}
