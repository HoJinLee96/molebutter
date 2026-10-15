package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.Change;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;

/** Provider-owned mapping boundary. Observations and remote bodies stay on the server. */
public interface MarketplaceProductAdapter {
    String market();
    String accountKey();
    boolean supports(Document document);
    default String readOnlyReason(Document document) { return null; }
    default Document normalize(Document document) { return document; }
    Document newDocument(Document reference);
    default void validateSettings(Long actor,Document document,boolean create,List<Change> changes) {}
    Document observe(Long actor, Document reference, Mapping mapping);
    record Observation(Document document,NaverEditor.Context naverContext) {}
    default Observation observeTarget(Long actor,Document reference,Mapping mapping) { return new Observation(observe(actor,reference,mapping),null); }
    default boolean supportsPendingEdits() { return false; }
    default MarketplaceEditing.PendingRebase rebase(Document observed,Document latest,List<Change> changes) {
        throw new cc.ataglace.molebutter.common.api.InputValidationFailure("이 마켓의 임시 변경 저장은 지원하지 않습니다.");
    }
    /** Persist only the provider-owned input; shared reference fields and other providers must survive. */
    default Document saveTarget(Document reference,Document desired) { return reference; }
    /** Typed comparable values only: never raw provider source or external identifiers. */
    default List<Change> comparisonFields(Document document) { return List.of(); }
    default List<Change> observedComparisonFields(Document document){return comparisonFields(document);}
    default List<MarketplaceEditing.ConflictKey> comparisonKnown(Document document,List<MarketplaceEditing.ConflictKey> known,Document transmitted){return known;}
    default Object comparisonValue(Document document,Change field){return field.value();}
    default String comparisonLabel(Change field){return null;}
    default String comparisonSource(Document document,Change field){return null;}
    default Object comparisonDisplay(Document document,Change field){return comparisonValue(document,field);}
    default boolean comparisonSupported(Change field){return true;}
    default String comparisonIssue(Document document,Change field){return null;}
    default String replayReason(Document document,List<Change> fields){return readOnlyReason(document);}
    default List<Change> normalizeChanges(Document document,List<Change> changes) { return changes; }
    Document apply(Document document, List<Change> changes);
    default Mapping legacyMapping(Document document, String accountKey) { return null; }
}
