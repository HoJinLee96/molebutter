package cc.ataglace.molebutter.marketplace.api;

import java.util.List;

/** A short-lived observation, separate from saved registration defaults and explicit write intent. */
public interface MarketplaceEditing {
    Session start(Long actor, String draftId);
    Session saveReference(Long actor, String sessionId, MarketplaceDrafts.Document reference);
    Session refresh(Long actor, String sessionId, String market);

    record Session(String id, String draftId, long revision, String expiresAt, List<Target> targets) {}
    record Target(String market, String mode, String status, String observedAt, String error,
                  MarketplaceDrafts.Document document) {}
    /** Only whitelisted typed app fields are accepted; no remote account/product identifiers or raw body. */
    record Change(String path, String optionId, Object value) {}
    record TargetChanges(String market, List<Change> changes) {}
    record PrepareRequest(String draftId, Long revision, String sessionId, boolean requested,
                          List<TargetChanges> targets) {}
}
