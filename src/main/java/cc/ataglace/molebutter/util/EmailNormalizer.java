package cc.ataglace.molebutter.util;

import java.util.Locale;
import java.util.regex.Pattern;

import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;

/** 이메일 정규화(trim + 소문자) + 형식 검증. 저장·조회·중복검사 전 반드시 거친다. */
public final class EmailNormalizer {

    /** 실용적 이메일 패턴(local@domain.tld). 소문자화 이후 검사하므로 소문자 범위만 둔다. 선형 매칭이라 긴 입력에도 안전하다. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$");

    /** User.email 컬럼 길이와 동일한 상한. */
    private static final int MAX_LENGTH = 255;

    private EmailNormalizer() {
    }

    /** 정규화 후 이메일 형식이 아니면 {@link ErrorCode#INVALID_EMAIL}을 던진다. */
    public static String normalize(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_EMAIL);
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.length() > MAX_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.INVALID_EMAIL);
        }
        return email;
    }
}
