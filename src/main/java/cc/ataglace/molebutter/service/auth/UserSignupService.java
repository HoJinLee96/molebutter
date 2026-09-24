package cc.ataglace.molebutter.service.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.dto.auth.SignupRequest;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.infra.web.ClientInfo;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.audit.UserAuthAuditService;
import cc.ataglace.molebutter.util.EmailNormalizer;
import cc.ataglace.molebutter.util.PhoneNumberNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 직원 가입: 이메일 인증번호 발송 → 검증 → 가입 제출(PENDING, 대표 승인 시 ACTIVE·역할 지정). */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSignupService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final EmailVerificationService emailVerificationService;
    private final UserAuthAuditService auditService;

    /** 가입용 인증번호 발송. 이미 가입된 이메일은 발송 전에 명시 거절한다. */
    public void sendSignupCode(String rawEmail, ClientInfo client) {
        String email = EmailNormalizer.normalize(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        emailVerificationService.sendCode(email, EmailVerificationPurpose.SIGNUP);
        auditService.register(null, null, email, UserAuthEventType.EMAIL_CODE_SENT, client, true, null);
    }

    /** 가입용 인증번호 검증(성공 시 verified 플래그 — 제출 단계에서 확인·소비). */
    public void verifySignupCode(String rawEmail, String code) {
        emailVerificationService.verifyCode(EmailNormalizer.normalize(rawEmail), code, EmailVerificationPurpose.SIGNUP);
    }

    /**
     * 가입 제출: 정책/정규화 검증 → verified 원자 소비 → PENDING/VIEWER 계정 저장.
     * 인증 증명은 저장 전에 소비한다. 저장 실패 시 재인증이 필요하다.
     */
    public void signup(SignupRequest request, ClientInfo client) {
        String email = EmailNormalizer.normalize(request.email());
        passwordPolicyValidator.validate(request.password());
        String phoneNumber = PhoneNumberNormalizer.normalize(request.phoneNumber());
        String name = request.name().trim();

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        emailVerificationService.consumeVerified(email, EmailVerificationPurpose.SIGNUP);
        User user = User.builder()
                .email(email)
                .phoneNumber(phoneNumber)
                .name(name)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.VIEWER) // 가입 직후 최소 권한 — 실제 역할(직원 종류)은 승인 시 대표가 지정한다
                .status(UserStatus.PENDING)
                .build();
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 사전 existsByEmail 이후 동시 제출 레이스: unique 충돌을 동일 안내로 수렴시킨다.
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        auditService.register(user.getRole(), user.getId(), email, UserAuthEventType.SIGNUP, client, true, null);
        log.info("[SIGNUP] 승인 대기 계정 생성. email={} name={}", email, name);
    }
}
