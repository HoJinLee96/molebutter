package cc.ataglace.molebutter.storage.api;

import java.util.Objects;

/** The bounded payload is copied at both boundaries so callers cannot mutate the stored result. */
public record StoredObject(ObjectMetadata metadata, byte[] content) {
    public StoredObject {
        Objects.requireNonNull(metadata, "metadata");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
