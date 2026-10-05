package cc.ataglace.molebutter.identity.internal;
import cc.ataglace.molebutter.identity.internal.JpaPages;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cc.ataglace.molebutter.identity.api.UserAuthEventType;
import cc.ataglace.molebutter.identity.internal.UserAuthAuditLog;
import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.identity.api.AuthLogResponse;
import cc.ataglace.molebutter.identity.internal.UserAuthAuditLogRepository;
import lombok.RequiredArgsConstructor;

/** 인증 감사 로그 조회 — 대표 전용 AUTH_LOGS 섹션. */
@Service
@RequiredArgsConstructor
public class DefaultAuthLogQueryService implements cc.ataglace.molebutter.identity.api.AuthLogQueryService {

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
        return JpaPages.from(result.map(DefaultAuthLogQueryService::snapshot));
    }

    private static String normalizeFilter(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
    private static AuthLogResponse snapshot(UserAuthAuditLog log) {
        return new AuthLogResponse(
                String.valueOf(log.getId()),
                log.getUserRole(),
                log.getUserId() == null ? null : String.valueOf(log.getUserId()),
                log.getEmail(),
                log.getEventType(),
                log.getEventType().getLabel(),
                log.getIp(),
                log.getUserAgent(),
                log.isSuccess(),
                log.getFailureReason(),
                log.getCreatedAt());
    }
}
