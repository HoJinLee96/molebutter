package cc.ataglace.molebutter.marketplace.api;

import java.util.List;

/** A short-lived observation, separate from saved registration defaults and explicit write intent. */
public interface MarketplaceEditing {
    Session start(Long actor, String draftId);
    default Session startLazy(Long actor,String draftId,String initialMarket){return start(actor,draftId);}
    Session saveReference(Long actor, String sessionId, MarketplaceDrafts.Document reference);
    DraftSaveResult saveDraft(Long actor, String sessionId, DraftSaveRequest request);
    Session refresh(Long actor, String sessionId, String market);

    record Session(String id, String draftId, long revision, String expiresAt, List<Target> targets) {}
    record Target(String market, String mode, String status, String observedAt, String error,
                  MarketplaceDrafts.Document document, List<Change> pendingChanges,
                  List<Conflict> conflicts, NaverEditor.Context naverContext, ListingInfo listing, Comparison comparison) {
        public Target { pendingChanges=pendingChanges==null?List.of():List.copyOf(pendingChanges); conflicts=conflicts==null?List.of():List.copyOf(conflicts); }
        public Target(String market,String mode,String status,String observedAt,String error,MarketplaceDrafts.Document document,List<Change> pendingChanges,List<Conflict> conflicts,NaverEditor.Context naverContext){this(market,mode,status,observedAt,error,document,pendingChanges,conflicts,naverContext,null);}
        public Target(String market,String mode,String status,String observedAt,String error,MarketplaceDrafts.Document document,List<Change> pendingChanges,List<Conflict> conflicts,NaverEditor.Context naverContext,ListingInfo listing){this(market,mode,status,observedAt,error,document,pendingChanges,conflicts,naverContext,listing,null);}
        public Target withListing(ListingInfo value){return new Target(market,mode,status,observedAt,error,document,pendingChanges,conflicts,naverContext,value,comparison);}
        public Target withComparison(Comparison value){return new Target(market,mode,status,observedAt,error,document,pendingChanges,conflicts,naverContext,listing,value);}
        public Target(String market,String mode,String status,String observedAt,String error,MarketplaceDrafts.Document document) {
            this(market,mode,status,observedAt,error,document,List.of(),List.of(),null);
        }
        public Target(String market,String mode,String status,String observedAt,String error,MarketplaceDrafts.Document document,List<Change> pendingChanges,List<Conflict> conflicts) {
            this(market,mode,status,observedAt,error,document,pendingChanges,conflicts,null);
        }
    }
    record Comparison(String token,String savedAt,List<ComparisonRow> rows,Integer policyVersion) {
        public Comparison(String token,String savedAt,List<ComparisonRow> rows){this(token,savedAt,rows,2);}
    }
    record ComparisonRow(String key,List<ConflictKey> fields,Object observed,Object saved,boolean observedKnown,boolean savedKnown,boolean adoptAllowed,boolean replayAllowed,String reason,
                         String kind,String label,String source,Object observedDisplay,Object savedDisplay,String evidenceToken) {
        public ComparisonRow(String key,List<ConflictKey> fields,Object observed,Object saved,boolean observedKnown,boolean savedKnown,boolean adoptAllowed,boolean replayAllowed,String reason){this(key,fields,observed,saved,observedKnown,savedKnown,adoptAllowed,replayAllowed,reason,savedKnown?"DIFFERENCE":"INFORMATION",null,null,observed,saved,null);}
    }
    record Resolution(String key,String action) {}
    record Resolve(String sessionId,String token,Long revision,String market,Long listingVersion,String comparisonToken,String requestId,List<Resolution> choices) {}
    record ResolveResult(boolean applied,String reason,MarketplaceDrafts.Document reference,Session session,String token) {}
    record ListingInfo(String state,String reason,String checkedAt,long version,boolean recreateAllowed) {}
    /** Only whitelisted typed app fields are accepted; no remote account/product identifiers or raw body. */
    record Change(String path, String optionId, Object value) {}
    record TargetChanges(String market, List<Change> changes,List<ConflictKey> resolvedConflicts) {
        public TargetChanges { resolvedConflicts=resolvedConflicts==null?List.of():List.copyOf(resolvedConflicts); }
        public TargetChanges(String market,List<Change> changes){this(market,changes,List.of());}
    }
    record ConflictKey(String path,String optionId) {}
    record Conflict(String path,String optionId,Object before,Object current,Object desired) {}
    record PendingRebase(List<Change> changes,List<Conflict> conflicts) {}
    record DraftSaveRequest(MarketplaceDrafts.Document reference,List<TargetChanges> targets) {}
    record DraftSaveResult(MarketplaceDrafts.Document reference,Session session) {}
    record PrepareRequest(String draftId, Long revision, String sessionId, boolean requested,
                          List<TargetChanges> targets) {}
}
