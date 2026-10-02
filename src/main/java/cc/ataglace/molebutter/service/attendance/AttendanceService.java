package cc.ataglace.molebutter.service.attendance;

import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cc.ataglace.molebutter.domain.*;
import cc.ataglace.molebutter.domain.attendance.*;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.attendance.AttendanceDtos.*;
import cc.ataglace.molebutter.exception.*;
import cc.ataglace.molebutter.repository.*;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {
    private final AttendanceRepository records;
    private final AttendanceCorrectionRepository corrections;
    private final UserRepository users;
    private final AttendanceTime time;
    private final ObjectMapper json;
    private static final List<AttendanceStatus> ACTIVE = List.of(AttendanceStatus.WORKING, AttendanceStatus.ON_BREAK);

    public CurrentView current(Long userId) {
        LocalDateTime now = time.now();
        return new CurrentView(now, view(active(userId), now),
                view(records.findByUserIdAndWorkDate(userId, now.toLocalDate()).orElse(null), now));
    }

    public PageResponse<RecordView> list(Long userId, String month, int page) {
        YearMonth m = month(month);
        LocalDateTime now = time.now();
        return PageResponse.from(records.findByUserIdAndWorkDateBetween(userId, m.atDay(1), m.atEndOfMonth(),
                page(page, "workDate")).map(a -> view(a, now)));
    }

    public RecordView detail(Long userId, Long id) {
        return view(records.findByIdAndUserId(id, userId).orElseThrow(this::notFound), time.now());
    }

    @Transactional
    public RecordView clockIn(Long userId, ClockInRequest request) {
        lockUser(userId, true);
        LocalDateTime now = time.now();
        if (records.findByUserIdAndWorkDate(userId, now.toLocalDate()).isPresent()) {
            throw conflict("오늘 출근 기록이 이미 있습니다. 최신 기록을 확인해 주세요.");
        }
        Attendance previous = active(userId);
        if (previous != null) {
            if (!previous.getWorkDate().isBefore(now.toLocalDate()) || !Boolean.TRUE.equals(request.confirmMissing())) {
                throw conflict("진행 중인 근무가 있습니다. 이전 근무 처리 방법을 선택해 주세요.");
            }
            expect(previous, request.previousRecordId(), request.previousRevision());
            previous.markMissing();
        } else if (request.previousRecordId() != null || request.previousRevision() != null || Boolean.TRUE.equals(request.confirmMissing())) {
            throw conflict("이전 근무 상태가 변경되었습니다. 새로고침 후 다시 시도해 주세요.");
        }
        Attendance prior = records.findFirstByUserIdAndWorkDateLessThanOrderByWorkDateDesc(userId, now.toLocalDate()).orElse(null);
        if (prior != null && prior.getClockOut() != null && prior.getClockOut().isAfter(now)) {
            throw conflict("이전 근무의 퇴근 시각을 확인해 주세요.");
        }
        return view(records.saveAndFlush(new Attendance(userId, now)), now);
    }

    @Transactional
    public RecordView action(Long userId, String action, ActionRequest request) {
        lockUser(userId, true);
        Attendance a = records.findByIdAndUserId(id(request.recordId()), userId).orElseThrow(this::notFound);
        expect(a, request.recordId(), request.revision());
        if (!a.isActive()) throw conflict("이미 종료되거나 누락 처리된 근무입니다. 정정 요청을 이용해 주세요.");
        LocalDateTime now = time.now();
        LocalDateTime last = a.getBreaks().isEmpty() ? a.getClockIn() :
                Optional.ofNullable(a.getBreaks().getLast().getEndedAt()).orElse(a.getBreaks().getLast().getStartedAt());
        if (now.isBefore(last)) throw conflict("서버 시각이 마지막 기록보다 이릅니다. 잠시 후 다시 시도해 주세요.");
        switch (action) {
            case "clock-out" -> a.clockOut(now);
            case "break-start" -> {
                if (a.getStatus() != AttendanceStatus.WORKING) throw conflict("이미 휴게 중입니다.");
                a.startBreak(now);
            }
            case "break-end" -> {
                if (a.getStatus() != AttendanceStatus.ON_BREAK) throw conflict("진행 중인 휴게가 없습니다.");
                a.endBreak(now);
            }
            default -> throw new IllegalArgumentException("지원하지 않는 근태 동작입니다.");
        }
        return view(a, now);
    }

    public PageResponse<CorrectionView> myCorrections(Long userId, String month, int page) {
        YearMonth m = month(month);
        return PageResponse.from(corrections.findByUserIdAndWorkDateBetween(userId, m.atDay(1), m.atEndOfMonth(),
                page(page, "createdAt")).map(this::correctionView));
    }

    public CorrectionView myCorrection(Long userId, Long id) {
        return correctionView(corrections.findByIdAndUserId(id, userId).orElseThrow(this::notFound));
    }

    @Transactional
    public CorrectionView requestCorrection(Long userId, CorrectionRequest request) {
        lockUser(userId, true);
        Attendance original = records.findByUserIdAndWorkDate(userId, request.workDate()).orElse(null);
        if (original != null) {
            expect(original, request.recordId(), request.revision());
            if (original.isActive()) throw conflict("진행 중인 근무는 먼저 퇴근 처리해 주세요.");
        } else if (request.recordId() != null || request.revision() != null) {
            throw conflict("원본 기록이 변경되었습니다. 다시 조회해 주세요.");
        }
        if (corrections.existsByUserIdAndWorkDateAndStatus(userId, request.workDate(), CorrectionStatus.PENDING)) {
            throw conflict("해당 날짜에 승인 대기 중인 정정 요청이 있습니다. 취소 후 다시 신청해 주세요.");
        }
        Snapshot proposed = new Snapshot(request.clockIn(), request.clockOut(), AttendanceStatus.COMPLETED,
                request.breaks().stream().sorted(Comparator.comparing(BreakTime::startedAt)).toList());
        validateSchedule(userId, request.workDate(), proposed);
        AttendanceCorrection c = new AttendanceCorrection(userId, request.workDate(), original,
                original == null ? null : json.writeValueAsString(Snapshot.from(original)),
                json.writeValueAsString(proposed), request.reason().trim());
        return correctionView(corrections.saveAndFlush(c));
    }

    @Transactional
    public CorrectionView cancel(Long userId, Long requestId, ReviewRequest request) {
        lockUser(userId, true);
        AttendanceCorrection c = corrections.findByIdAndUserId(requestId, userId).orElseThrow(this::notFound);
        expectPending(c, request.revision());
        c.cancel();
        return correctionView(c);
    }

    public PageResponse<CorrectionView> reviewList(Long actor, CorrectionStatus status, int page) {
        requireAdmin(actor);
        return PageResponse.from(corrections.search(status, page(page, "createdAt")).map(this::correctionView));
    }

    public CorrectionView reviewDetail(Long actor, Long requestId) {
        requireAdmin(actor);
        return correctionView(corrections.findById(requestId).orElseThrow(this::notFound));
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CorrectionView review(Long actor, Long requestId, boolean approve, ReviewRequest request) {
        // 소유자 ID만 먼저 읽는다. 엔티티는 사용자 잠금 후 읽어 오래된 영속성 컨텍스트 값을 피한다.
        Long owner = corrections.findOwner(requestId).orElseThrow(this::notFound);
        lockUser(owner, false);
        requireAdmin(actor);
        AttendanceCorrection c = corrections.findById(requestId).orElseThrow(this::notFound);
        expectPending(c, request.revision());
        String comment = request.comment() == null ? null : request.comment().trim();
        if (!approve && (comment == null || comment.isBlank())) throw new IllegalArgumentException("반려 사유를 입력해 주세요.");
        if (approve) {
            Attendance current = records.findByUserIdAndWorkDate(owner, c.getWorkDate()).orElse(null);
            if (c.getAttendanceId() == null) {
                if (current != null) throw conflict("신청 후 출근 기록이 생성되었습니다. 요청을 취소·반려하고 다시 신청해 주세요.");
            } else {
                if (current == null || !current.getId().equals(c.getAttendanceId())
                        || current.getRevision() != c.getBaseRevision() || current.isActive()) {
                    throw conflict("신청 후 원본이 변경되었습니다. 요청을 취소·반려하고 다시 신청해 주세요.");
                }
            }
            Snapshot proposed = snapshot(c.getProposedSnapshot());
            validateSchedule(owner, c.getWorkDate(), proposed);
            if (current == null) current = new Attendance(owner, proposed.clockIn());
            current.correct(proposed.clockIn(), proposed.clockOut(), proposed.breaks().stream()
                    .map(b -> new AttendanceBreak(b.startedAt(), b.endedAt())).toList());
            records.save(current);
        }
        c.review(approve, actor, time.now(), comment);
        return correctionView(c);
    }

    /** 이전·다음 근무와 구간을 비교하며, 누락된 퇴근 시각은 추측하지 않는다. */
    private void validateSchedule(Long userId, LocalDate date, Snapshot p) {
        LocalDateTime now = time.now();
        if (p.clockIn() == null || p.clockOut() == null || !date.equals(p.clockIn().toLocalDate())
                || !p.clockOut().isAfter(p.clockIn()) || p.clockOut().isAfter(now)) {
            throw new IllegalArgumentException("출근 날짜와 시간을 확인해 주세요. 퇴근은 출근 이후이며 미래 시각일 수 없습니다.");
        }
        LocalDateTime lastEnd = p.clockIn();
        for (BreakTime b : p.breaks()) {
            if (b.startedAt() == null || b.endedAt() == null || b.startedAt().isBefore(lastEnd)
                    || b.endedAt().isBefore(b.startedAt()) || b.endedAt().isAfter(p.clockOut())) {
                throw new IllegalArgumentException("휴게시간은 근무 구간 안에 있어야 하며 서로 겹칠 수 없습니다.");
            }
            lastEnd = b.endedAt();
        }
        Attendance previous = records.findFirstByUserIdAndWorkDateLessThanOrderByWorkDateDesc(userId, date).orElse(null);
        if (previous != null) {
            if (previous.isActive()) throw conflict("이전 날짜에 진행 중인 근무가 있습니다. 먼저 마감해 주세요.");
            LocalDateTime boundary = previous.getClockOut();
            if (boundary == null) {
                boundary = previous.getClockIn();
                for (AttendanceBreak b : previous.getBreaks()) {
                    LocalDateTime known = b.getEndedAt() == null ? b.getStartedAt() : b.getEndedAt();
                    if (known.isAfter(boundary)) boundary = known;
                }
            }
            if (p.clockIn().isBefore(boundary)) throw conflict("이전 근무의 기록된 시간과 겹칩니다. 해당 기록을 먼저 정정해 주세요.");
        }
        Attendance next = records.findFirstByUserIdAndWorkDateGreaterThanOrderByWorkDateAsc(userId, date).orElse(null);
        if (next != null && p.clockOut().isAfter(next.getClockIn())) throw conflict("다음 근무의 출근 시각과 겹칩니다.");
    }

    private User lockUser(Long id, boolean checkActive) {
        User user = users.findLockedById(id).orElseThrow(this::notFound);
        if (checkActive && user.isSigninBlocked()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return user;
    }

    private void requireAdmin(Long actor) {
        User user = users.findById(actor).orElseThrow(this::notFound);
        if (user.isSigninBlocked() || user.getRole() != UserRole.ADMIN) throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
    }

    private Attendance active(Long userId) {
        return records.findFirstByUserIdAndStatusInOrderByWorkDateDesc(userId, ACTIVE).orElse(null);
    }

    private void expect(Attendance a, String expectedId, Long revision) {
        if (!a.getId().toString().equals(expectedId) || revision == null || a.getRevision() != revision) {
            throw conflict("근태 상태가 변경되었습니다. 최신 기록을 확인하고 다시 시도해 주세요.");
        }
    }

    private void expectPending(AttendanceCorrection c, Long revision) {
        if (c.getStatus() != CorrectionStatus.PENDING || revision == null || revision != c.getRevision()) {
            throw conflict("이미 처리되었거나 변경된 정정 요청입니다. 최신 상태를 확인해 주세요.");
        }
    }

    private RecordView view(Attendance a, LocalDateTime now) {
        if (a == null) return null;
        Long work = null, rest = null;
        if (a.getStatus() != AttendanceStatus.MISSING) {
            LocalDateTime end = a.getClockOut() == null ? now : a.getClockOut();
            rest = a.getBreaks().stream().mapToLong(b -> Math.max(0,
                    Duration.between(b.getStartedAt(), b.getEndedAt() == null ? end : b.getEndedAt()).getSeconds())).sum();
            work = Math.max(0, Duration.between(a.getClockIn(), end).getSeconds() - rest);
        }
        return new RecordView(a.getId().toString(), a.getWorkDate(), a.getRevision(), Snapshot.from(a), work, rest);
    }

    private CorrectionView correctionView(AttendanceCorrection c) {
        String name = users.findById(c.getUserId()).map(User::getName).orElse("삭제된 사용자");
        String reviewer = c.getReviewedBy() == null ? null : users.findById(c.getReviewedBy()).map(User::getName).orElse("삭제된 사용자");
        return new CorrectionView(c.getId().toString(), c.getUserId().toString(), name, c.getWorkDate(), c.getRevision(),
                c.getStatus(), c.getReason(), snapshot(c.getOriginalSnapshot()), snapshot(c.getProposedSnapshot()), c.getCreatedAt(),
                c.getReviewedBy() == null ? null : c.getReviewedBy().toString(), reviewer, c.getReviewedAt(), c.getReviewComment(),
                c.getUserId().equals(c.getReviewedBy()));
    }

    private Snapshot snapshot(String value) { return value == null ? null : json.readValue(value, Snapshot.class); }
    private Long id(String value) {
        try { return Long.valueOf(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException("기록 ID를 확인해 주세요."); }
    }
    private YearMonth month(String value) {
        if (value == null || value.isBlank()) return YearMonth.from(time.now());
        try { return YearMonth.parse(value); } catch (java.time.format.DateTimeParseException ex) { throw new IllegalArgumentException("조회 월은 YYYY-MM 형식으로 입력해 주세요."); }
    }
    private Pageable page(int page, String sort) { return PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, sort, "id")); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND); }
    private IllegalStateException conflict(String message) { return new IllegalStateException(message); }
}
