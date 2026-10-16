package cc.ataglace.molebutter.coupang.api;

import java.util.List;
import cc.ataglace.molebutter.common.api.PageResponse;

/**
 * Persistent external submissions. A preview is not an external write request
 * body.
 */
public interface CoupangSubmissions {
    Preview prepare(Long actor, CoupangEditing.PrepareRequest request);
    Execution execute(Long actor, String previewId, String idempotencyKey);
    Execution get(Long actor, String executionId);
    PageResponse<Execution> list(Long actor, String workingCopyId, int page, int size);
    Execution retry(Long actor, String executionId);
    Execution reconcile(Long actor, String executionId);
    /**
     * Retire only a confirmed failed intent before preparing corrected inputs. No
     * external write.
     */
    Execution revise(Long actor, String executionId);

    enum Status {
        QUEUED, RUNNING, SUCCEEDED, ACCEPTED, PARTIAL, FAILED, UNKNOWN
    }
    enum StepType {
        CREATE, PRODUCT, DELIVERY, ORIGINAL_PRICE, PRICE, STOCK, APPROVAL, VERIFY
    }
    record Change(String path, String before, String after) {
    }
    record PlannedStep(String id, StepType type, String optionId, String label) {
    }
    record PreviewTarget(String mode, List<PlannedStep> steps, List<Change> changes,
            List<CoupangWorkingCopies.Issue> issues, java.util.Map<String, String> optionNames) {
    }
    record Preview(String id, String workingCopyId, long revision, String expiresAt, boolean executable,
            boolean requested, PreviewTarget target) {
    }
    record Step(String id, StepType type, String optionId, String label, Status status, int attempts, String code,
            String message, String updatedAt) {
    }
    record Target(String mode, Status status, String externalProductId, List<Step> steps) {
    }
    record Execution(String id, String workingCopyId, long revision, Status status, String createdAt, String updatedAt,
            Target target, boolean revised) {
    }
}
