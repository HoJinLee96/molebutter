package cc.ataglace.molebutter.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import org.springframework.stereotype.Service;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.service.EmailSender;
import cc.ataglace.molebutter.service.KeyValueStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이메일 인증번호 발송·검증. 상태는 전부 {@link KeyValueStore}(TTL)로 관리한다.
 *
 * <p>흐름: sendCode(코드 저장+발송, 재발송 쿨다운) → verifyCode(시도 횟수 제한, 성공 시 verified 플래그)
 * → isVerified(제출 단계 확인) → consumeVerified(본 작업 커밋 후 소비).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String CODE_KEY = "auth:email:code:";
    private static final String VERIFIED_KEY = "auth:email:verified:";
    private static final String COOLDOWN_KEY = "auth:email:cooldown:";
    private static final String ATTEMPTS_KEY = "auth:email:attempts:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyValueStore store;
    private final EmailSender emailSender;
    private final AuthProperties authProperties;

    /** 인증번호 발송. 쿨다운 중이면 거절하고, 발송 실패 시 쿨다운을 되돌려 곧바로 재시도할 수 있게 한다. */
    public void sendCode(String email, EmailVerificationPurpose purpose) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        AuthProperties.EmailVerification cfg = authProperties.emailVerification();
        String cooldownKey = key(COOLDOWN_KEY, purpose, email);
        if (store.exists(cooldownKey)) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_COOLDOWN);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        store.put(key(CODE_KEY, purpose, email), code, cfg.codeTtl());
        store.delete(key(ATTEMPTS_KEY, purpose, email)); // 새 코드 발급 시 검증 시도 횟수 초기화
        store.put(cooldownKey, "1", cfg.sendCooldown());

        try {
            emailSender.send(email, "[molebutter] 이메일 인증번호",
                    "인증번호: " + code + "\n" + cfg.codeTtl().toMinutes() + "분 안에 입력해 주세요.");
        } catch (RuntimeException e) {
            store.delete(cooldownKey);
            log.error("인증번호 메일 발송 실패. email={} purpose={}", email, purpose, e);
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    /** 인증번호 검증. 성공 시 코드를 소비하고 verified 플래그를 남긴다. 시도 횟수 초과 시 코드를 폐기한다. */
    public void verifyCode(String email, String code, EmailVerificationPurpose purpose) {
        if (email == null || email.isBlank() || code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_EMAIL_CODE);
        }
        AuthProperties.EmailVerification cfg = authProperties.emailVerification();
        String codeKey = key(CODE_KEY, purpose, email);
        String attemptsKey = key(ATTEMPTS_KEY, purpose, email);

        long attempts = store.increment(attemptsKey, cfg.codeTtl());
        if (attempts > cfg.maxVerifyAttempts()) {
            store.delete(codeKey);
            throw new BusinessException(ErrorCode.INVALID_EMAIL_CODE);
        }

        String saved = store.get(codeKey).orElse(null);
        if (saved == null || !MessageDigest.isEqual(
                saved.getBytes(StandardCharsets.UTF_8), code.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.INVALID_EMAIL_CODE);
        }

        store.delete(codeKey);
        store.delete(attemptsKey);
        store.put(key(VERIFIED_KEY, purpose, email), "1", cfg.verifiedTtl());
    }

    /** 해당 용도의 이메일 인증이 완료된 상태인지. */
    public boolean isVerified(String email, EmailVerificationPurpose purpose) {
        return email != null && store.exists(key(VERIFIED_KEY, purpose, email));
    }

    /** verified 플래그 소비(가입 등 본 작업 커밋 후 호출). */
    public void consumeVerified(String email, EmailVerificationPurpose purpose) {
        if (email == null) {
            return;
        }
        store.delete(key(VERIFIED_KEY, purpose, email));
    }

    private String key(String prefix, EmailVerificationPurpose purpose, String email) {
        return prefix + purpose.name() + ":" + email;
    }
}
