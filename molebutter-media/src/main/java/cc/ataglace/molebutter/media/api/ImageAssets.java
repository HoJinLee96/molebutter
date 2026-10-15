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
    default Asset editedMetadata(Long actor,String id){throw new cc.ataglace.molebutter.common.api.InputValidationFailure("기존 로컬 이미지를 R2 이미지로 다시 선택해 주세요.");}
    default AssetContent readEdited(Long actor,String id){editedMetadata(actor,id);return read(actor,id);}
    AssetContent published(String token);
    String publicUrl(Long actor, String id);
    String submissionUrl(Long actor, String id);

    /** Replace draft references within the caller's active database transaction. */
    void replaceReferences(Long actor, String draftId, Set<String> ids);

    /** Pin a submission snapshot within the caller's active database transaction. */
    default void pinListingHistory(Long actor,long historyId,String draftId,java.util.Set<String> ids) {}
    void pinReferences(Long actor, long submissionId, String draftId, Set<String> ids);

    /** Release a snapshot already determined to be expired and unexecuted by its owner. */
    void releaseReferences(long submissionId);

    /** Bounded maintenance sweep; active draft and submission references always protect the original. */
    void reapUnreferenced();

    record Asset(String id, String url, String mimeType, int width, int height, long bytes) { }
    record AssetContent(Asset metadata, byte[] bytes) { }
}
