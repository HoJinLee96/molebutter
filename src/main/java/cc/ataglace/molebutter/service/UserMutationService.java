package cc.ataglace.molebutter.service;

import java.time.LocalDateTime;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserMutationService {
    
    private final UserRepository userRepository;

    /** 로그인 성공 반영(실패 카운터·잠금 초기화, last_login_at 갱신). 갱신된 계정을 반환한다. */
    @Transactional
    public User registerSigninSuccess(Long userId, long expectedVersion, String expectedHash, LocalDateTime now) {
        User user = userRepository.findLockedById(userId).orElseThrow();
        if (user.isSigninBlocked() || user.getAuthVersion() != expectedVersion
                || !user.getPasswordHash().equals(expectedHash)) {
            throw new BusinessException(ErrorCode.SIGNIN_FAILED);
        }
        user.signinSuccess(now);
        return user;
    }

    /**
     * 로그인 실패 반영(실패 카운터 증가, 임계값 초과 시 LOCKED 상태로 전환).
     *
     * @return 이번 실패로 새로 잠금되었으면 true
     */
    @Transactional
    public boolean registerSigninFailure(Long userId, int maxFailedAttempts, LocalDateTime now) {
        User user = userRepository.findLockedById(userId).orElseThrow();
        return user.signinFailure(maxFailedAttempts, now);
    }


}
