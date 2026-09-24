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
 * → consumeVerified(본 작업 전에 원자적으로 소비; 작업 실패 시 재인증).
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
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        if (!store.putIfAbsent(cooldownKey, code, cfg.sendCooldown())) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_COOLDOWN);
        }

        store.put(key(CODE_KEY, purpose, email), code, cfg.codeTtl());
        store.delete(key(ATTEMPTS_KEY, purpose, email)); // 새 코드 발급 시 검증 시도 횟수 초기화
        store.delete(key(VERIFIED_KEY, purpose, email));

        try {
            emailSender.send(email, "[molebutter] 이메일 인증번호",
                    "인증번호: " + code + "\n" + cfg.codeTtl().toMinutes() + "분 안에 입력해 주세요.");
        } catch (RuntimeException e) {
            store.compareAndDelete(key(CODE_KEY, purpose, email), code);
            store.compareAndDelete(cooldownKey, code);
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

        if (!store.compareAndDelete(codeKey, saved)) {
            throw new BusinessException(ErrorCode.INVALID_EMAIL_CODE);
        }
        store.delete(attemptsKey);
        store.put(key(VERIFIED_KEY, purpose, email), "1", cfg.verifiedTtl());
    }

    /** DB 변경 전에 인증 증명을 한 번만 소비한다. DB 실패 시 재인증해야 한다. */
    public void consumeVerified(String email, EmailVerificationPurpose purpose) {
        if (email == null || store.getAndDelete(key(VERIFIED_KEY, purpose, email)).isEmpty()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    private String key(String prefix, EmailVerificationPurpose purpose, String email) {
        return prefix + purpose.name() + ":" + email;
    }
}
