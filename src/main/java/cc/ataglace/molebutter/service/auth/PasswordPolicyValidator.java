package cc.ataglace.molebutter.service.auth;

import org.springframework.stereotype.Component;

import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;

/** 비밀번호 정책: 8~64자, 영문자·숫자 각 1자 이상, 공백 불가. */
@Component
public class PasswordPolicyValidator {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64; // BCrypt 입력 상한(72바이트) 이내

    public void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_FORMAT);
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < rawPassword.length(); i++) {
            char c = rawPassword.charAt(i);
            if (Character.isWhitespace(c)) {
                throw new BusinessException(ErrorCode.INVALID_PASSWORD_FORMAT);
            }
            hasLetter |= (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            hasDigit |= c >= '0' && c <= '9';
        }
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_FORMAT);
        }
    }
}
