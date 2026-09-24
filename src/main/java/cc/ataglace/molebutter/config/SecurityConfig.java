package cc.ataglace.molebutter.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import cc.ataglace.molebutter.domain.MenuSection;
import cc.ataglace.molebutter.dto.ApiResponse;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** 로그인 없이 접근 가능한 페이지. */
    private static final String[] PUBLIC_PAGES = {
            "/signin", "/signup", "/find-id", "/find-password", "/error"
    };

    /** 정적 리소스. */
    private static final String[] STATIC_RESOURCES = {
            "/css/**", "/js/**", "/images/**", "/favicon.ico"
    };

    /** 로그인 없이 접근 가능한 인증 API(로그인·갱신·가입·로그아웃·계정 찾기·비밀번호 재설정). */
    private static final String[] PUBLIC_AUTH_APIS = {
            "/api/auth/csrf", "/api/auth/signin", "/api/auth/refresh", "/api/auth/signout",
            "/api/auth/signup", "/api/auth/signup/**",
            "/api/auth/find-email",
            "/api/auth/password-reset", "/api/auth/password-reset/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;

    @Value("${app.security.require-https:false}")
    private boolean requireHttps;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = new CookieCsrfTokenRepository();
        csrfRepository.setCookieCustomizer(cookie -> cookie
                .secure(authProperties.cookie().secure()).sameSite("Lax").path("/"));
        if (requireHttps) http.redirectToHttps(Customizer.withDefaults());
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable) // 로그아웃은 /api/auth/signout에서 직접 처리
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_PAGES).permitAll();
                    auth.requestMatchers(STATIC_RESOURCES).permitAll();
                    auth.requestMatchers(PUBLIC_AUTH_APIS).permitAll();
                    // 섹션별 인가: MenuSection 선언(pagePatterns/apiPatterns)에서 자동 생성.
                    // JwtAuthenticationFilter가 role → PERM_<섹션> 권한을 부여하는 것과 짝을 이룬다.
                    for (MenuSection section : MenuSection.values()) {
                        String authority = MenuSection.PERMISSION_PREFIX + section.name();
                        if (section.getPagePatterns().length > 0) {
                            auth.requestMatchers(section.getPagePatterns()).hasAuthority(authority);
                        }
                        if (section.getApiPatterns().length > 0) {
                            auth.requestMatchers(section.getApiPatterns()).hasAuthority(authority);
                        }
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 미인증: API는 401 JSON, 페이지는 로그인 화면으로 리다이렉트. */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            if (isApiRequest(request)) {
                writeErrorBody(response, ErrorCode.UNAUTHORIZED);
            } else {
                response.sendRedirect("/signin");
            }
        };
    }

    /** 권한 부족: API는 403 JSON, 페이지는 403 에러 페이지(/error). */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            if (isApiRequest(request)) {
                writeErrorBody(response, accessDeniedException instanceof CsrfException
                        ? ErrorCode.INVALID_CSRF_TOKEN : ErrorCode.HANDLE_ACCESS_DENIED);
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
        };
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    /** 필터 단계는 GlobalExceptionHandler 밖이라 ApiResponse 형식을 직접 맞춰 내려준다. */
    private void writeErrorBody(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode)));
    }
}
