package cc.ataglace.molebutter.app.internal;


import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.web.csrf.CsrfToken;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cc.ataglace.molebutter.identity.api.UserAccount;
import cc.ataglace.molebutter.identity.api.UserAuthEventType;
import cc.ataglace.molebutter.app.internal.ApiResponse;
import cc.ataglace.molebutter.identity.api.FindEmailRequest;
import cc.ataglace.molebutter.identity.api.FindEmailResponse;
import cc.ataglace.molebutter.identity.api.MeResponse;
import cc.ataglace.molebutter.identity.api.PasswordChangeRequest;
import cc.ataglace.molebutter.identity.api.PasswordResetCodeRequest;
import cc.ataglace.molebutter.identity.api.PasswordResetRequest;
import cc.ataglace.molebutter.identity.api.PasswordResetVerifyRequest;
import cc.ataglace.molebutter.identity.api.SigninRequest;
import cc.ataglace.molebutter.identity.api.SignupEmailCodeRequest;
import cc.ataglace.molebutter.identity.api.SignupEmailVerifyRequest;
import cc.ataglace.molebutter.identity.api.SignupRequest;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.identity.api.ClientInfo;
import cc.ataglace.molebutter.identity.api.IdentityAccounts;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.identity.api.UserAuthAuditService;
import cc.ataglace.molebutter.app.internal.AuthCookieService;
import cc.ataglace.molebutter.identity.api.AuthTokenService;
import cc.ataglace.molebutter.identity.api.AuthTokenService.TokenBundle;
import cc.ataglace.molebutter.identity.api.UserAuthService;
import cc.ataglace.molebutter.identity.api.UserEmailFindService;
import cc.ataglace.molebutter.identity.api.UserPasswordChangeService;
import cc.ataglace.molebutter.identity.api.UserPasswordResetService;
import cc.ataglace.molebutter.identity.api.UserSignupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserAuthController {

    private final UserSignupService userSignupService;
    private final UserAuthService userAuthService;
    private final AuthTokenService authTokenService;
    private final AuthCookieService authCookieService;
    private final UserPasswordChangeService passwordChangeService;
    private final UserPasswordResetService passwordResetService;
    private final UserEmailFindService emailFindService;
    private final UserAuthAuditService auditService;
    private final IdentityAccounts userRepository;

    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<Map<String, String>>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                Map.of("headerName", token.getHeaderName(), "token", token.getToken())));
    }

    @PostMapping("/signin")
    public ResponseEntity<ApiResponse<Void>> signin(
            @Valid @RequestBody SigninRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        ClientInfo client = cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest);
        UserAccount user = userAuthService.signin(request.email(), request.password(), client);
        TokenBundle tokens = authTokenService.issueOnSignin(user);
        authCookieService.writeTokens(httpResponse, tokens.accessToken(), tokens.refreshToken());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        ClientInfo client = cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest);
        String refreshToken = authCookieService.readRefreshCookie(httpRequest);
        try {
            TokenBundle tokens = authTokenService.rotate(refreshToken);
            authCookieService.writeTokens(httpResponse, tokens.accessToken(), tokens.refreshToken());
            auditService.register(tokens.user().getRole(), tokens.user().getId(), tokens.user().getEmail(),
                    UserAuthEventType.TOKEN_REFRESH, client, true, null);
            return ResponseEntity.ok(ApiResponse.success());
        } catch (BusinessException e) {
            // 회전 실패: cookie를 비우고 401을 직접 반환한다(throw 시 Set-Cookie 유실 방지).
            authCookieService.clearTokens(httpResponse);
            auditService.register(null, null, null, UserAuthEventType.TOKEN_REFRESH, client, false,
                    e.getErrorCode().name());
            return ResponseEntity.status(HttpErrorStatus.of(e.getErrorCode())).body(ApiResponse.error(e.getErrorCode()));
        }
    }

    @PostMapping("/signout")
    public ResponseEntity<ApiResponse<Void>> signout(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            @AuthenticationPrincipal UserPrincipal principal) {
        String accessToken = authCookieService.resolveAccessToken(httpRequest);
        String refreshToken = authCookieService.readRefreshCookie(httpRequest);
        authTokenService.revokeOnLogout(accessToken, refreshToken);
        authCookieService.clearTokens(httpResponse);
        auditService.register(
                principal == null ? null : principal.userRole(),
                principal == null ? null : principal.userId(),
                principal == null ? null : principal.email(),
                UserAuthEventType.SIGNOUT, cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest), true, null);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UserAccount user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return ResponseEntity.ok(ApiResponse.success(MeResponse.from(user)));
    }

    @PostMapping("/password/change")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UserAccount user = passwordChangeService.changePassword(
                principal.userId(), request.currentPassword(), request.newPassword());

        // 기존 access token은 blacklist, 새 토큰쌍(새 family, 변경 이후 발급)으로 교체해 세션을 유지하되
        // 변경 이전 발급된 다른 access/refresh는 auth_version 검사로 거부된다.
        authTokenService.blacklistAccess(authCookieService.resolveAccessToken(httpRequest));
        TokenBundle tokens = authTokenService.issueOnSignin(user);
        authCookieService.writeTokens(httpResponse, tokens.accessToken(), tokens.refreshToken());
        auditService.register(user.getRole(), user.getId(), user.getEmail(),
                UserAuthEventType.PASSWORD_CHANGED, cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest), true, null);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/signup/email-code")
    public ResponseEntity<ApiResponse<Void>> sendEmailCode(
            @Valid @RequestBody SignupEmailCodeRequest request, HttpServletRequest httpRequest) {
        userSignupService.sendSignupCode(request.email(), cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/signup/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@Valid @RequestBody SignupEmailVerifyRequest request) {
        userSignupService.verifySignupCode(request.email(), request.code());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> signup(
            @Valid @RequestBody SignupRequest request, HttpServletRequest httpRequest) {
        userSignupService.signup(request, cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    /** 이메일(로그인 계정) 찾기 — 이름+휴대전화 일치 시 마스킹된 이메일 안내. */
    @PostMapping("/find-email")
    public ResponseEntity<ApiResponse<FindEmailResponse>> findEmail(@Valid @RequestBody FindEmailRequest request) {
        String maskedEmail = emailFindService.findMaskedEmail(request.name(), request.phoneNumber());
        return ResponseEntity.ok(ApiResponse.success(new FindEmailResponse(maskedEmail)));
    }

    @PostMapping("/password-reset/email-code")
    public ResponseEntity<ApiResponse<Void>> sendPasswordResetCode(
            @Valid @RequestBody PasswordResetCodeRequest request, HttpServletRequest httpRequest) {
        passwordResetService.sendResetCode(request.email(), cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/password-reset/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyPasswordResetCode(
            @Valid @RequestBody PasswordResetVerifyRequest request) {
        passwordResetService.verifyResetCode(request.email(), request.code());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/password-reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
        passwordResetService.resetPassword(request.email(), request.newPassword(), cc.ataglace.molebutter.app.internal.RequestClientInfo.from(httpRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }
}
