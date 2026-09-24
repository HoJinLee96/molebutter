package cc.ataglace.molebutter.service.auth;

import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.repository.UserRepository;
import lombok.RequiredArgsConstructor;

/** 본인 비밀번호 변경. 인증 버전을 올려 변경 이전 access/refresh 토큰을 모두 무효화한다. */
@Service
@RequiredArgsConstructor
public class UserPasswordChangeService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;

    @Transactional
    public User changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findLockedById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        passwordPolicyValidator.validate(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_SAME_AS_OLD);
        }
        user.changePassword(passwordEncoder.encode(newPassword), LocalDateTime.now());
        return user;
    }
}
