package cc.ataglace.molebutter.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record SigninRequest(
        @NotBlank String email,
        @NotBlank String password) {

}
