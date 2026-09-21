package cc.ataglace.molebutter.service.auth;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthCookieService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties authProperties;

    /** 요청에서 access token을 결정한다: Authorization Bearer 헤더 우선, 없으면 access cookie 폴백. */
    public String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return readAccessCookie(request);
    }

    public void writeTokens(HttpServletResponse response, String accessToken, String refreshToken) {
        AuthProperties.Cookie cfg = authProperties.cookie();
        addCookie(response, cfg.accessName(), accessToken, "/", null);
        addCookie(response, cfg.refreshName(), refreshToken, cfg.refreshPath(), null);
    }

    /** 로그아웃/만료 시 두 cookie를 즉시 만료시킨다. */
    public void clearTokens(HttpServletResponse response) {
        AuthProperties.Cookie cfg = authProperties.cookie();
        addCookie(response, cfg.accessName(), "", "/", Duration.ZERO);
        addCookie(response, cfg.refreshName(), "", cfg.refreshPath(), Duration.ZERO);
    }

    public String readAccessCookie(HttpServletRequest request) {
        return readCookie(request, authProperties.cookie().accessName());
    }

    public String readRefreshCookie(HttpServletRequest request) {
        return readCookie(request, authProperties.cookie().refreshName());
    }

    /** maxAge가 null이면 session cookie(Max-Age 미지정, 브라우저 종료 시 소멸)로 발급한다. */
    private void addCookie(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(true)
                .secure(authProperties.cookie().secure())
                .path(path)
                .sameSite("Lax");
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
