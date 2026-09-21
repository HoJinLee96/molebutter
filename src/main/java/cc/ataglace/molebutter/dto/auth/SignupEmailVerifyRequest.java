package cc.ataglace.molebutter.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupEmailVerifyRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Size(max = 10) String code) {

}
