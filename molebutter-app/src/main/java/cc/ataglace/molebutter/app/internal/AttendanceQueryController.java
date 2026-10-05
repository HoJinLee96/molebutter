package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.attendance.api.AttendanceQueryDtos.*;


import java.io.IOException;
import java.nio.file.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cc.ataglace.molebutter.attendance.api.AttendanceStatus;
import cc.ataglace.molebutter.app.internal.ApiResponse;
import cc.ataglace.molebutter.common.api.PageResponse;

import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.attendance.api.AttendanceQueryService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/attendance-manage")
public class AttendanceQueryController {
    private final AttendanceQueryService attendance;
    @GetMapping("/records")
    public ApiResponse<PageResponse<RecordRow>> records(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String q, @RequestParam(required = false) Long userId,
            @RequestParam(required = false) AttendanceStatus status, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(attendance.records(user.userId(), from, to, q, userId, status, page));
    }
    @GetMapping("/records/{id}")
    public ApiResponse<RecordRow> detail(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
        return ApiResponse.success(attendance.detail(user.userId(), id));
    }
    @GetMapping("/summary")
    public ApiResponse<PageResponse<SummaryRow>> summary(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String month, @RequestParam(required = false) String q,
            @RequestParam(required = false) Long userId, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(attendance.summary(user.userId(), month, q, userId, page));
    }
    @GetMapping("/records.csv")
    public void recordsCsv(@AuthenticationPrincipal UserPrincipal user, HttpServletResponse response,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String q, @RequestParam(required = false) Long userId,
            @RequestParam(required = false) AttendanceStatus status) throws IOException {
        send(response, attendance.recordsCsv(user.userId(), from, to, q, userId, status), "attendance-records.csv");
    }
    @GetMapping("/summary.csv")
    public void summaryCsv(@AuthenticationPrincipal UserPrincipal user, HttpServletResponse response,
            @RequestParam(required = false) String month, @RequestParam(required = false) String q,
            @RequestParam(required = false) Long userId) throws IOException {
        send(response, attendance.summaryCsv(user.userId(), month, q, userId), "attendance-summary.csv");
    }
    private void send(HttpServletResponse response, Path file, String name) throws IOException {
        try {
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
            response.setHeader("Cache-Control", "no-store");
            response.setContentLengthLong(Files.size(file));
            Files.copy(file, response.getOutputStream());
        } finally { Files.deleteIfExists(file); }
    }
}
