package cc.ataglace.molebutter.identity.api;


import jakarta.validation.constraints.NotBlank;

public record SigninRequest(
        @NotBlank String email,
        @NotBlank String password) {

}
