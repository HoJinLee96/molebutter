package cc.ataglace.molebutter.util;

import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;

/** 전화번호 정규화: 구분 기호 제거, +82 국가번호를 0으로 치환해 숫자만 저장한다. */
public final class PhoneNumberNormalizer {

    private PhoneNumberNormalizer() {
    }

    public static String normalize(String rawPhoneNumber) {
        if (rawPhoneNumber == null || rawPhoneNumber.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_NUMBER);
        }
        String trimmed = rawPhoneNumber.trim();
        if (trimmed.startsWith("+82")) {
            trimmed = "0" + trimmed.substring(3);
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        // 0으로 시작하는 9~11자리(02-xxx-xxxx ~ 010-xxxx-xxxx)만 허용한다.
        if (!digits.matches("0\\d{8,10}")) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_NUMBER);
        }
        return digits;
    }
}
