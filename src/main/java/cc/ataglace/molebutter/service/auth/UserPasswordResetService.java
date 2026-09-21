package cc.ataglace.molebutter.service.auth;

import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.infra.web.ClientInfo;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.audit.UserAuthAuditService;
import cc.ataglace.molebutter.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 비밀번호 재설정(find-password): 이메일 인증번호 발송 → 검증 → 새 비밀번호 제출.
 * LOCKED 계정의 복구 경로이기도 하다(재설정 성공 시 잠금 해제).
 * SUSPENDED(관리자 정지)는 재설정으로 우회할 수 없게 발송 단계부터 거절한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPasswordResetService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final EmailVerificationService emailVerificationService;
    private final UserAuthAuditService auditService;

    /** 재설정용 인증번호 발송. 가입된 이메일만 발송한다(가입 여부는 signup 쪽에서 이미 노출되므로 명시 거절이 UX상 낫다). */
    public void sendResetCode(String rawEmail, ClientInfo client) {
        String email = EmailNormalizer.normalize(rawEmail);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.isSuspended()) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        emailVerificationService.sendCode(email, EmailVerificationPurpose.PASSWORD_RESET);
        auditService.register(user.getRole(), user.getId(), email, UserAuthEventType.EMAIL_CODE_SENT, client, true,
                null);
    }

    /** 재설정용 인증번호 검증(성공 시 verified 플래그 — 제출 단계에서 확인·소비). */
    public void verifyResetCode(String rawEmail, String code) {
        emailVerificationService.verifyCode(EmailNormalizer.normalize(rawEmail), code,
                EmailVerificationPurpose.PASSWORD_RESET);
    }

    /**
     * 재설정 제출: verified 확인 → 정책 검증 → 해시 교체 + 잠금 해제.
     * passwordChangedAt 갱신으로 변경 이전 발급 refresh 토큰은 회전 시 전부 거부된다.
     */
    @Transactional
    public void resetPassword(String rawEmail, String newPassword, ClientInfo client) {
        String email = EmailNormalizer.normalize(rawEmail);
        if (!emailVerificationService.isVerified(email, EmailVerificationPurpose.PASSWORD_RESET)) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.isSuspended()) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        passwordPolicyValidator.validate(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_SAME_AS_OLD);
        }

        user.resetPassword(passwordEncoder.encode(newPassword), LocalDateTime.now());

        emailVerificationService.consumeVerified(email, EmailVerificationPurpose.PASSWORD_RESET);
        auditService.register(user.getRole(), user.getId(), email, UserAuthEventType.PASSWORD_RESET, client, true,
                null);
        log.info("[PASSWORD_RESET] 비밀번호 재설정 완료. userId={}", user.getId());
    }
}
