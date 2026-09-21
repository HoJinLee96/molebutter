package cc.ataglace.molebutter.config.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
    @NotNull @Valid Signin signin,
    @NotNull @Valid Jwt jwt,
    @NotNull @Valid Cookie cookie,
    @NotNull @Valid EmailVerification emailVerification
) {
    public record Signin(
            @Min(1) int maxFailedAttempts,
            @Min(1) int rateLimitMaxAttempts,
            @NotNull Duration rateLimitWindow
    ) {}

    public record Jwt (
         @NotBlank String secret,
         @NotNull Duration accessTtl,
         @NotNull Duration refreshTtl,
         @NotBlank String issuer
    ){}

    public record Cookie (
        @NotBlank String accessName,
        @NotBlank String refreshName,
        @NotBlank String refreshPath,
        boolean secure
    ){}

    public record EmailVerification (
        @NotNull Duration codeTtl,
        @NotNull Duration verifiedTtl,
        @NotNull Duration sendCooldown,
        @Min(1) int maxVerifyAttempts
    ){}
}
