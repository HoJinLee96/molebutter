package cc.ataglace.molebutter.security;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** 서버의 메서드·경로 규칙으로만 분류한다. 클라이언트가 보낸 정책 값은 사용하지 않는다. */
@Component
public class RequestAuthPolicyResolver {

    // 개인정보·업무 데이터가 없는 공통 조회만 정확한 경로로 추가한다.
    // 버전 이력은 현재 메뉴만 존재한다. 화면 구현 시에도 이 조건을 유지해야 한다.
    private final List<RequestMatcher> basicReads = List.of(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/version-history"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.HEAD, "/version-history"));

    public RequestAuthPolicy resolve(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod())) {
            // OPTIONS 등도 DB 생략 대상으로 자동 허용하지 않는다. CSRF의 대상 판단과는 별개다.
            return RequestAuthPolicy.WRITE;
        }
        if (basicReads.stream().anyMatch(matcher -> matcher.matches(request))) {
            return RequestAuthPolicy.BASIC_READ;
        }
        // 새 보호 경로를 추가해도 기본적으로 최신 계정 상태를 확인한다.
        return RequestAuthPolicy.SENSITIVE_READ;
    }
}
