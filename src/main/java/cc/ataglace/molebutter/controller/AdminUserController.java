package cc.ataglace.molebutter.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.dto.ApiResponse;
import cc.ataglace.molebutter.dto.admin.UserApproveRequest;
import cc.ataglace.molebutter.dto.admin.UserRoleChangeRequest;
import cc.ataglace.molebutter.dto.admin.UserSummaryResponse;
import cc.ataglace.molebutter.infra.web.ClientInfo;
import cc.ataglace.molebutter.infra.web.OperationAudit;
import cc.ataglace.molebutter.security.UserPrincipal;
import cc.ataglace.molebutter.service.admin.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 대표 전용 직원 계정 관리 API. 인가는 SecurityConfig의 섹션 규칙(PERM_USER_MANAGE = /api/admin/users/**)이 담당한다.
 * 모든 변경 행위는 @OperationAudit(행위자·HTTP 관점) + 서비스의 인증 감사(대상 계정 관점)로 이중 기록된다.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService userAdminService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> list(
            @RequestParam(required = false) UserStatus status) {
        return ResponseEntity.ok(ApiResponse.success(userAdminService.listUsers(status)));
    }

    @OperationAudit(value = "USER_APPROVE", targetType = "USER", targetIdPathVariable = "userId")
    @PostMapping("/{userId}/approve")
    public ResponseEntity<ApiResponse<Void>> approve(
            @PathVariable Long userId,
            @Valid @RequestBody UserApproveRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        userAdminService.approve(principal.userId(), userId, request.role(), ClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @OperationAudit(value = "USER_ROLE_CHANGE", targetType = "USER", targetIdPathVariable = "userId")
    @PostMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<Void>> changeRole(
            @PathVariable Long userId,
            @Valid @RequestBody UserRoleChangeRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        userAdminService.changeRole(principal.userId(), userId, request.role(), ClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @OperationAudit(value = "USER_SUSPEND", targetType = "USER", targetIdPathVariable = "userId")
    @PostMapping("/{userId}/suspend")
    public ResponseEntity<ApiResponse<Void>> suspend(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        userAdminService.suspend(principal.userId(), userId, ClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @OperationAudit(value = "USER_UNSUSPEND", targetType = "USER", targetIdPathVariable = "userId")
    @PostMapping("/{userId}/unsuspend")
    public ResponseEntity<ApiResponse<Void>> unsuspend(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        userAdminService.unsuspend(principal.userId(), userId, ClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @OperationAudit(value = "USER_UNLOCK", targetType = "USER", targetIdPathVariable = "userId")
    @PostMapping("/{userId}/unlock")
    public ResponseEntity<ApiResponse<Void>> unlock(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        userAdminService.unlock(principal.userId(), userId, ClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }
}
