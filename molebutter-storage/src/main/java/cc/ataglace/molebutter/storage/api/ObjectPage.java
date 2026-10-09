package cc.ataglace.molebutter.storage.api;

import java.util.List;

/** Null nextContinuationToken means the listing is complete. */
public record ObjectPage(List<ObjectMetadata> objects, String nextContinuationToken) {
    public ObjectPage {
        objects = List.copyOf(objects);
    }
}
