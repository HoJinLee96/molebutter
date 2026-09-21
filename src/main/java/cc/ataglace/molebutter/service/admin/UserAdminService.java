package cc.ataglace.molebutter.service.admin;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.dto.admin.UserSummaryResponse;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.infra.web.ClientInfo;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.audit.UserAuthAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대표 전용 직원 계정 관리. 상태 전이 규칙은 {@link User}의 도메인 메서드가 지키고,
 * 여기서는 조회·본인 계정 가드·감사 기록을 담당한다.
 * (행위자·HTTP 정보는 컨트롤러의 @OperationAudit 인터셉터가 별도로 남긴다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final Sort LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final UserRepository userRepository;
    private final UserAuthAuditService auditService;

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> listUsers(UserStatus status) {
        List<User> users = status == null
                ? userRepository.findAll(LIST_SORT)
                : userRepository.findAllByStatus(status, LIST_SORT);
        return users.stream().map(UserSummaryResponse::from).toList();
    }

    /** 가입 승인 + 역할 지정. */
    @Transactional
    public void approve(Long actorId, Long userId, UserRole role, ClientInfo client) {
        User user = load(userId);
        user.approve(role);
        auditService.register(user.getRole(), user.getId(), user.getEmail(),
                UserAuthEventType.ACCOUNT_APPROVED, client, true, null);
        log.info("[USER_MANAGE] 가입 승인. userId={} role={} by={}", userId, role, actorId);
    }

    /** 역할 변경. 새 역할은 대상자의 다음 access token 발급 시점(최대 access TTL)부터 반영된다. */
    @Transactional
    public void changeRole(Long actorId, Long userId, UserRole role, ClientInfo client) {
        guardSelf(actorId, userId);
        User user = load(userId);
        user.changeRole(role);
        auditService.register(user.getRole(), user.getId(), user.getEmail(),
                UserAuthEventType.ACCOUNT_ROLE_CHANGED, client, true, null);
    }

    /** 계정 정지(PENDING이면 가입 거절). 기존 refresh는 회전 시 차단되고, access는 남은 TTL 동안만 유효하다. */
    @Transactional
    public void suspend(Long actorId, Long userId, ClientInfo client) {
        guardSelf(actorId, userId);
        User user = load(userId);
        user.suspend();
        auditService.register(user.getRole(), user.getId(), user.getEmail(),
                UserAuthEventType.ACCOUNT_SUSPENDED, client, true, null);
    }

    /** 정지 해제. */
    @Transactional
    public void unsuspend(Long actorId, Long userId, ClientInfo client) {
        User user = load(userId);
        user.unsuspend();
        auditService.register(user.getRole(), user.getId(), user.getEmail(),
                UserAuthEventType.ACCOUNT_UNSUSPENDED, client, true, null);
    }

    /** 잠금 해제(비밀번호 재설정 없이 대표가 풀어주는 경로). */
    @Transactional
    public void unlock(Long actorId, Long userId, ClientInfo client) {
        User user = load(userId);
        user.unlock();
        auditService.register(user.getRole(), user.getId(), user.getEmail(),
                UserAuthEventType.ACCOUNT_UNLOCKED, client, true, null);
    }

    private User load(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /** 대표가 본인 계정을 정지·강등하는 사고 방지. */
    private void guardSelf(Long actorId, Long userId) {
        if (actorId != null && actorId.equals(userId)) {
            throw new IllegalArgumentException("본인 계정은 여기서 변경할 수 없습니다.");
        }
    }
}
