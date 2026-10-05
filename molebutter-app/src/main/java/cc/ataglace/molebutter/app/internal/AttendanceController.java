package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.attendance.api.AttendanceDtos.*;


import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import cc.ataglace.molebutter.app.internal.ApiResponse;
import cc.ataglace.molebutter.common.api.PageResponse;

import cc.ataglace.molebutter.app.internal.OperationAudit;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.attendance.api.AttendanceService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {
    private final AttendanceService attendance;

    @GetMapping("/current")
    public ApiResponse<CurrentView> current(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(attendance.current(user.userId()));
    }
    @GetMapping
    public ApiResponse<PageResponse<RecordView>> list(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(attendance.list(user.userId(), month, page));
    }
    @GetMapping("/{id}")
    public ApiResponse<RecordView> detail(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id) {
        return ApiResponse.success(attendance.detail(user.userId(), id));
    }
    @PostMapping("/clock-in")
    @OperationAudit(value = "ATTENDANCE_CLOCK_IN", targetType = "ATTENDANCE")
    public ApiResponse<RecordView> clockIn(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody ClockInRequest request) {
        return ApiResponse.success(attendance.clockIn(user.userId(), request));
    }
    @PostMapping("/clock-out")
    @OperationAudit(value = "ATTENDANCE_CLOCK_OUT", targetType = "ATTENDANCE")
    public ApiResponse<RecordView> clockOut(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody ActionRequest request) {
        return ApiResponse.success(attendance.action(user.userId(), "clock-out", request));
    }
    @PostMapping("/break-start")
    @OperationAudit(value = "ATTENDANCE_BREAK_START", targetType = "ATTENDANCE")
    public ApiResponse<RecordView> breakStart(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody ActionRequest request) {
        return ApiResponse.success(attendance.action(user.userId(), "break-start", request));
    }
    @PostMapping("/break-end")
    @OperationAudit(value = "ATTENDANCE_BREAK_END", targetType = "ATTENDANCE")
    public ApiResponse<RecordView> breakEnd(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody ActionRequest request) {
        return ApiResponse.success(attendance.action(user.userId(), "break-end", request));
    }
    @GetMapping("/corrections")
    public ApiResponse<PageResponse<CorrectionView>> corrections(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(attendance.myCorrections(user.userId(), month, page));
    }
    @GetMapping("/corrections/{id}")
    public ApiResponse<CorrectionView> correction(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id) {
        return ApiResponse.success(attendance.myCorrection(user.userId(), id));
    }
    @PostMapping("/corrections")
    @OperationAudit(value = "ATTENDANCE_CORRECTION_REQUEST", targetType = "ATTENDANCE_CORRECTION")
    public ApiResponse<CorrectionView> request(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody CorrectionRequest request) {
        return ApiResponse.success(attendance.requestCorrection(user.userId(), request));
    }
    @PostMapping("/corrections/{id}/cancel")
    @OperationAudit(value = "ATTENDANCE_CORRECTION_CANCEL", targetType = "ATTENDANCE_CORRECTION", targetIdPathVariable = "id")
    public ApiResponse<CorrectionView> cancel(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(attendance.cancel(user.userId(), id, request));
    }
}
