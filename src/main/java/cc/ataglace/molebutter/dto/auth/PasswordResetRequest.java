package cc.ataglace.molebutter.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 재설정 최종 제출. 코드 검증은 앞 단계(verified 플래그)에서 끝났으므로 새 비밀번호만 받는다. */
public record PasswordResetRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank String newPassword) {

}
