package cc.ataglace.molebutter.service.admin;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.domain.audit.UserAuthAuditLog;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.admin.AuthLogResponse;
import cc.ataglace.molebutter.repository.UserAuthAuditLogRepository;
import lombok.RequiredArgsConstructor;

/** 인증 감사 로그 조회 — 대표 전용 AUTH_LOGS 섹션. */
@Service
@RequiredArgsConstructor
public class AuthLogQueryService {

    private static final int DEFAULT_RANGE_DAYS = 7;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserAuthAuditLogRepository repository;

    @Transactional(readOnly = true)
    public PageResponse<AuthLogResponse> search(LocalDate from, LocalDate to, UserAuthEventType eventType,
            String email, String ip, int page, int size) {
        LocalDate fromDate = from != null ? from : LocalDate.now().minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate toDate = to != null ? to : LocalDate.now();
        String emailFilter = normalizeFilter(email);
        String ipFilter = normalizeFilter(ip);

        Page<UserAuthAuditLog> result = repository.search(
                fromDate.atStartOfDay(),
                toDate.plusDays(1).atStartOfDay(), // 반개구간: to 당일 전체 포함
                eventType,
                emailFilter,
                ipFilter,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.from(result.map(AuthLogResponse::from));
    }

    private static String normalizeFilter(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
