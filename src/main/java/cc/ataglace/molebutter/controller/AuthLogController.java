package cc.ataglace.molebutter.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.dto.ApiResponse;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.admin.AuthLogResponse;
import cc.ataglace.molebutter.service.admin.AuthLogQueryService;
import lombok.RequiredArgsConstructor;

/** 대표 전용 인증 로그 조회 API. 인가는 섹션 규칙(PERM_AUTH_LOGS = /api/auth-logs/**)이 담당한다. */
@RestController
@RequestMapping("/api/auth-logs")
@RequiredArgsConstructor
public class AuthLogController {

    private final AuthLogQueryService authLogQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AuthLogResponse>>> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UserAuthEventType eventType,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String ip,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                ApiResponse.success(authLogQueryService.search(from, to, eventType, email, ip, page, size)));
    }
}
