package cc.ataglace.molebutter.media.api;

import java.util.Set;

/**
 * Owned original image assets. Originals stay private; explicitly published images use a capability URL.
 * Storage locations and marketplace CDN URLs are deliberately separate from this asset identity.
 */
public interface ImageAssets {
    Asset upload(Long actor, byte[] bytes);
    AssetContent read(Long actor, String id);
    Asset metadata(Long actor, String id);
    AssetContent published(String token);
    String publicUrl(Long actor, String id);
    String submissionUrl(Long actor, String id);

    /** Replace draft references within the caller's active database transaction. */
    void replaceReferences(Long actor, String draftId, Set<String> ids);

    /** Pin a submission snapshot within the caller's active database transaction. */
    void pinReferences(Long actor, long submissionId, String draftId, Set<String> ids);

    /** Release a snapshot already determined to be expired and unexecuted by its owner. */
    void releaseReferences(long submissionId);

    /** Bounded maintenance sweep; active draft and submission references always protect the original. */
    void reapUnreferenced();

    record Asset(String id, String url, String mimeType, int width, int height, long bytes) { }
    record AssetContent(Asset metadata, byte[] bytes) { }
}
