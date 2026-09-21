package cc.ataglace.molebutter.infra.web;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.security.UserPrincipal;
import cc.ataglace.molebutter.service.audit.OperationAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OperationAuditInterceptor implements HandlerInterceptor {

    private final OperationAuditService operationAuditService;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        OperationAudit annotation = handlerMethod.getMethodAnnotation(OperationAudit.class);
        if (annotation == null) {
            return;
        }

        boolean success = ex == null && response.getStatus() < 400;
        String failureReason = ex != null ? ex.getClass().getSimpleName()
                : (success ? null : "HTTP " + response.getStatus());

        ClientInfo clientInfo = ClientInfo.from(request);
        String targetType = annotation.targetType().isBlank() ? null : annotation.targetType();
        String targetId = resolveTargetId(annotation, request);
        Long userId = null;
        String userEmail = null;
        UserRole userRole = null;

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal userPrincipal) {
            userId = userPrincipal.userId();
            userEmail = userPrincipal.email();
            userRole = userPrincipal.userRole();
        } else {
            log.error("운영 감사 도중 인증 정보가 없어 기록 생략. eventType={}, method={}, uri={}, status={}, ip={}, userAgent={}",
                    annotation.value(),
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    clientInfo.ip(),
                    clientInfo.userAgent());
            return;
        }

        // 작동 감사 로그 기록
        operationAuditService.register(userRole, userId, userEmail, annotation.value(), targetType, targetId,
                request.getMethod(), request.getRequestURI(), clientInfo.ip(), clientInfo.userAgent(),
                success, failureReason);
    }

    private String resolveTargetId(OperationAudit annotation, HttpServletRequest request) {
        if (!annotation.targetIdPathVariable().isBlank()) {
            Object variablesAttribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (variablesAttribute instanceof Map<?, ?> variables) {
                String value = blankToNull(variables.get(annotation.targetIdPathVariable()));
                if (value != null) {
                    return value;
                }
            }
        }
        if (!annotation.targetIdRequestParam().isBlank()) {
            return blankToNull(request.getParameter(annotation.targetIdRequestParam()));
        }
        return null;
    }

    private String blankToNull(Object value) {
        return value == null ? null : value.toString().isBlank() ? null : value.toString();
    }

}
