package cc.ataglace.molebutter.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import cc.ataglace.molebutter.domain.MenuSection;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.auth.AuthCookieService;
import cc.ataglace.molebutter.service.auth.JwtService;
import cc.ataglace.molebutter.service.auth.AuthTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * access token(Authorization Bearer 헤더 우선, 없으면 cookie)을 검증해 SecurityContext에
 * {@link UserPrincipal} 인증을 넣는다.
 *
 * <p>토큰이 없거나 유효하지 않으면 예외 없이 익명으로 통과시킨다 — 401/403 응답은 인가 계층
 * (SecurityConfig의 authorizeHttpRequests + entry point)의 몫이다.
 *
 * <p>명시한 일반 조회는 JWT의 사용자 정보를 사용한다. 민감 조회·변경 요청은 DB의 상태·버전·역할을 확인한다.
 * 모든 분류에서 Redis의 토큰/세션 폐기를 확인하고, 역할별 섹션 권한은 서버에서 도출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthTokenService authTokenService;
    private final AuthCookieService authCookieService;
    private final UserRepository userRepository;
    private final RequestAuthPolicyResolver policyResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request) {
        String token = authCookieService.resolveAccessToken(request);
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtService.parse(token);
            if (!jwtService.isType(claims, JwtService.TYPE_ACCESS)) {
                return;
            }
            // 로그아웃/비밀번호 변경으로 폐기된 access는 만료 전이라도 거부한다.
            if (authTokenService.isAccessBlacklisted(claims.getId())) {
                return;
            }
            Long userId = jwtService.userId(claims);
            if (userId == null || authTokenService.isFamilyRevoked(jwtService.familyId(claims))) return;
            UserPrincipal principal = resolvePrincipal(request, claims, userId);
            if (principal == null) return;
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken
                    .authenticated(principal, null, buildAuthorities(principal.userRole()));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException e) {
            // 서명 불일치·만료·형식 오류: 익명으로 계속(인가 계층에서 401 처리).
            log.debug("[AUTH] access token 검증 실패(익명 처리): {}", e.getMessage());
        }
    }

    private UserPrincipal resolvePrincipal(HttpServletRequest request, Claims claims, Long userId) {
        if (policyResolver.resolve(request).requiresCurrentUser()) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.isSigninBlocked() || !authTokenService.hasCurrentVersion(claims, user)) return null;
            return new UserPrincipal(userId, user.getEmail(), user.getRole());
        }

        // DB를 생략하는 조회에서는 계정 상태/역할 변경이 access 만료까지 지연될 수 있다.
        String email = jwtService.email(claims);
        String role = jwtService.role(claims);
        if (email == null || email.isBlank() || role == null || claims.get("ver", Number.class) == null
                || claims.getId() == null || claims.getId().isBlank() || claims.getExpiration() == null) return null;
        try {
            return new UserPrincipal(userId, email, UserRole.valueOf(role));
        } catch (IllegalArgumentException e) {
            throw new JwtException("알 수 없는 사용자 역할", e);
        }
    }

    /** ROLE_<역할> + 역할이 가진 섹션별 PERM_<섹션> 권한을 부여한다. */
    private List<GrantedAuthority> buildAuthorities(UserRole role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        for (MenuSection section : role.getSections()) {
            authorities.add(new SimpleGrantedAuthority(MenuSection.PERMISSION_PREFIX + section.name()));
        }
        return authorities;
    }
}
