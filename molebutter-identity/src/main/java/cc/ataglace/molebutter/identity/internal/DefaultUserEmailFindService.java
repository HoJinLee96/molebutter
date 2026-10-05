package cc.ataglace.molebutter.identity.internal;


import org.springframework.stereotype.Service;

import cc.ataglace.molebutter.identity.internal.User;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.identity.internal.UserRepository;
import cc.ataglace.molebutter.identity.internal.PhoneNumberNormalizer;
import lombok.RequiredArgsConstructor;

/** 이메일(로그인 계정) 찾기: 이름+휴대전화로 조회해 마스킹된 이메일을 돌려준다. */
@Service
@RequiredArgsConstructor
public class DefaultUserEmailFindService implements cc.ataglace.molebutter.identity.api.UserEmailFindService {

    private final UserRepository userRepository;

    public String findMaskedEmail(String rawName, String rawPhoneNumber) {
        String name = rawName == null ? "" : rawName.trim();
        String phoneNumber = PhoneNumberNormalizer.normalize(rawPhoneNumber);
        User user = userRepository.findByNameAndPhoneNumber(name, phoneNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return mask(user.getEmail());
    }

    /** local part 앞 2자만 남기고 마스킹한다: abcdef@x.com → ab****@x.com */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        String local = email.substring(0, at);
        String visible = local.substring(0, Math.min(2, local.length()));
        return visible + "*".repeat(Math.max(local.length() - visible.length(), 2)) + email.substring(at);
    }
}
