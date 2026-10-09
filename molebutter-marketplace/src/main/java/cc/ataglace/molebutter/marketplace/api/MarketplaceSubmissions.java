package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import cc.ataglace.molebutter.common.api.PageResponse;

/** Persistent external submissions. A preview is not an external write request body. */
public interface MarketplaceSubmissions {
    /** Legacy intent-less preparation is deliberately not executable. */
    @Deprecated default Preview prepare(Long actor, String draftId, Long revision, boolean requested) {
        throw new MarketplaceSubmissionFailure(MarketplaceSubmissionFailure.Kind.INVALID);
    }
    Preview prepare(Long actor, MarketplaceEditing.PrepareRequest request);
    Execution execute(Long actor, String previewId, String idempotencyKey);
    Execution get(Long actor, String executionId);
    PageResponse<Execution> list(Long actor, String draftId, int page, int size);
    Execution retry(Long actor, String executionId);
    Execution reconcile(Long actor, String executionId);

    enum Status { QUEUED, RUNNING, SUCCEEDED, ACCEPTED, PARTIAL, FAILED, UNKNOWN }
    enum StepType { CREATE, PRODUCT, DELIVERY, ORIGINAL_PRICE, PRICE, STOCK, VERIFY }
    record Change(String path, String before, String after) {}
    record PlannedStep(String id, StepType type, String optionId, String label) {}
    record PreviewTarget(String market, String mode, List<PlannedStep> steps,
                         List<Change> changes, List<MarketplaceDrafts.Issue> issues,
                         java.util.Map<String,String> optionNames) {
        public PreviewTarget(String market,String mode,List<PlannedStep> steps,List<Change> changes,List<MarketplaceDrafts.Issue> issues){
            this(market,mode,steps,changes,issues,java.util.Map.of());
        }
    }
    record Preview(String id, String draftId, long revision, String expiresAt,
                   boolean executable, boolean requested, List<PreviewTarget> targets) {}
    record Step(String id, StepType type, String optionId, String label, Status status,
                int attempts, String code, String message, String updatedAt) {}
    record Target(String market, String mode, Status status, String externalProductId,
                  List<Step> steps) {}
    record Execution(String id, String draftId, long revision, Status status,
                     String createdAt, String updatedAt, List<Target> targets) {}
}
