package cc.ataglace.molebutter.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import cc.ataglace.molebutter.domain.attendance.CorrectionStatus;
import cc.ataglace.molebutter.dto.*;
import cc.ataglace.molebutter.dto.attendance.AttendanceDtos.*;
import cc.ataglace.molebutter.infra.web.OperationAudit;
import cc.ataglace.molebutter.security.UserPrincipal;
import cc.ataglace.molebutter.service.attendance.AttendanceService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/attendance-manage/corrections")
@RequiredArgsConstructor
public class AttendanceManageController {
    private final AttendanceService attendance;
    @GetMapping
    public ApiResponse<PageResponse<CorrectionView>> list(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) CorrectionStatus status, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(attendance.reviewList(user.userId(), status, page));
    }
    @GetMapping("/{id}")
    public ApiResponse<CorrectionView> detail(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id) {
        return ApiResponse.success(attendance.reviewDetail(user.userId(), id));
    }
    @PostMapping("/{id}/approve")
    @OperationAudit(value = "ATTENDANCE_CORRECTION_APPROVE", targetType = "ATTENDANCE_CORRECTION", targetIdPathVariable = "id")
    public ApiResponse<CorrectionView> approve(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(attendance.review(user.userId(), id, true, request));
    }
    @PostMapping("/{id}/reject")
    @OperationAudit(value = "ATTENDANCE_CORRECTION_REJECT", targetType = "ATTENDANCE_CORRECTION", targetIdPathVariable = "id")
    public ApiResponse<CorrectionView> reject(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(attendance.review(user.userId(), id, false, request));
    }
}
