package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.coupang.api.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupang/submissions")
public class CoupangSubmissionController {
    private final CoupangSubmissions submissions;
    public CoupangSubmissionController(CoupangSubmissions submissions) {
        this.submissions = submissions;
    }
    record ExecuteRequest(String idempotencyKey) {
    }
    @PostMapping("/prepare")
    @OperationAudit("COUPANG_SUBMISSION_PREPARE")
    public ApiResponse<CoupangSubmissions.Preview> prepare(@AuthenticationPrincipal UserPrincipal actor,
            @RequestBody CoupangEditing.PrepareRequest input) {
        return ApiResponse.success(submissions.prepare(actor.userId(), input));
    }
    @PostMapping("/{previewId}/execute")
    @OperationAudit("COUPANG_SUBMISSION_EXECUTE")
    public ApiResponse<CoupangSubmissions.Execution> execute(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String previewId, @RequestBody ExecuteRequest input) {
        return ApiResponse.success(submissions.execute(actor.userId(), previewId, input.idempotencyKey()));
    }
    @GetMapping("/{id}")
    public ApiResponse<CoupangSubmissions.Execution> get(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id) {
        return ApiResponse.success(submissions.get(actor.userId(), id));
    }
    @GetMapping
    public ApiResponse<PageResponse<CoupangSubmissions.Execution>> list(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam String workingCopyId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(submissions.list(actor.userId(), workingCopyId, page, size));
    }
    @PostMapping("/{id}/retry")
    @OperationAudit("COUPANG_SUBMISSION_RETRY")
    public ApiResponse<CoupangSubmissions.Execution> retry(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id) {
        return ApiResponse.success(submissions.retry(actor.userId(), id));
    }
    @PostMapping("/{id}/reconcile")
    @OperationAudit("COUPANG_SUBMISSION_RECONCILE")
    public ApiResponse<CoupangSubmissions.Execution> reconcile(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id) {
        return ApiResponse.success(submissions.reconcile(actor.userId(), id));
    }
    @PostMapping("/{id}/revise")
    @OperationAudit("COUPANG_SUBMISSION_REVISE")
    public ApiResponse<CoupangSubmissions.Execution> revise(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id) {
        return ApiResponse.success(submissions.revise(actor.userId(), id));
    }
}
