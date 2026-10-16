package cc.ataglace.molebutter.app.internal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoupangHttpContractTest {
    final ObjectMapper json = new ObjectMapper();
    @Test
    void retiredPayloadsAreRejectedAtTheirContractLocations() {
        for (String body : new String[]{"{\"markets\":{}}", "{\"document\":{\"common\":{}}}",
                "{\"document\":{\"options\":[{\"overrides\":{}}]}}",
                "{\"document\":{\"delivery\":{\"market\":\"NAVER\"}}}",
                "{\"document\":{\"options\":[{\"registration\":{\"markets\":{}}}]}}"})
            assertThatThrownBy(() -> CoupangRequestBodyAdvice.check(json.readTree(body)))
                    .isInstanceOf(InputValidationFailure.class);
        assertThatCode(() -> CoupangRequestBodyAdvice
                .check(json.readTree("{\"changes\":[{\"value\":{\"reference\":\"opaque\",\"common\":\"text\"}}]}")))
                .doesNotThrowAnyException();
    }
    @Test
    void bodyInspectionPreservesBytesAndEnforcesItsSizeLimit() throws Exception {
        var advice = new CoupangRequestBodyAdvice(json);
        byte[] bytes = "{\"sessionId\":\"1\",\"document\":{\"basic\":{\"brand\":\"한글\"}}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var input = new MockHttpInputMessage(bytes);
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        assertThat(advice.beforeBodyRead(input, null, null, null).getBody().readAllBytes()).isEqualTo(bytes);
        var large = new MockHttpInputMessage(new byte[11 * 1024 * 1024 + 1]);
        large.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        assertThatThrownBy(() -> advice.beforeBodyRead(large, null, null, null))
                .isInstanceOf(InputValidationFailure.class);
    }
    @Test
    void malformedJsonUsesTheBadRequestHandler() {
        for (String body : new String[]{"{\"sessionId\":", "{} {}", "{} trailing"}) {
            var input = new MockHttpInputMessage(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            assertThatThrownBy(() -> new CoupangRequestBodyAdvice(json).beforeBodyRead(input, null, null, null))
                    .as("invalid JSON: %s", body)
                    .isInstanceOf(org.springframework.http.converter.HttpMessageNotReadableException.class);
        }
    }
    @Test
    void structuredJsonUsesTheSameContractAndSizeGuard() throws Exception {
        var advice = new CoupangRequestBodyAdvice(json);
        for (String contentType : new String[]{"application/vnd.coupang+json", "application/problem+json"}) {
            var retired = new MockHttpInputMessage(
                    "{\"markets\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            retired.getHeaders().setContentType(MediaType.parseMediaType(contentType));
            assertThatThrownBy(() -> advice.beforeBodyRead(retired, null, null, null))
                    .isInstanceOf(InputValidationFailure.class);
            var large = new MockHttpInputMessage(new byte[11 * 1024 * 1024 + 1]);
            large.getHeaders().setContentType(MediaType.parseMediaType(contentType));
            assertThatThrownBy(() -> advice.beforeBodyRead(large, null, null, null))
                    .isInstanceOf(InputValidationFailure.class);
            var valid = new MockHttpInputMessage("{\"changes\":[{\"value\":{\"common\":\"opaque text\"}}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            valid.getHeaders().setContentType(MediaType.parseMediaType(contentType));
            assertThat(advice.beforeBodyRead(valid, null, null, null).getBody().readAllBytes())
                    .isEqualTo("{\"changes\":[{\"value\":{\"common\":\"opaque text\"}}]}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test
    void globalMappingsPreserveCodesAndNeverNotifyReadFailures() {
        var notifications = mock(FailureNotificationService.class);
        var handler = new GlobalExceptionHandler(notifications);
        var request = new MockHttpServletRequest("GET", "/api/coupang/orders/123/detail");
        var response = handler.coupangFailure(new CoupangFailure(CoupangFailure.Kind.TIMEOUT), request);
        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getBody().getCode()).isEqualTo("COUPANG_ORDER_TIMEOUT");
        verifyNoInteractions(notifications);
        request = new MockHttpServletRequest("PUT", "/api/coupang/working-copies/1");
        assertThat(handler.coupangEditingFailure(new CoupangEditingFailure(CoupangEditingFailure.Kind.EXPIRED), request)
                .getBody().getCode()).isEqualTo("COUPANG_WORKING_COPY_EXPIRED");
        verify(notifications).record(eq(request), anyString());
        request = new MockHttpServletRequest("POST", "/api/coupang/submissions/prepare");
        assertThat(
                handler.coupangEditingFailure(new CoupangEditingFailure(CoupangEditingFailure.Kind.CONFLICT), request)
                        .getBody().getCode())
                .isEqualTo("COUPANG_EDITING_CONFLICT");
        assertThat(handler
                .coupangSubmissionFailure(new CoupangSubmissionFailure(CoupangSubmissionFailure.Kind.INVALID), request)
                .getStatusCode().value()).isEqualTo(422);
    }
    @Test
    void failedCoupangWritesPublishOnlyToTheRequestingUser() {
        var notices = mock(cc.ataglace.molebutter.operations.api.NotificationService.class);
        var failures = new FailureNotificationService(notices);
        var principal = new cc.ataglace.molebutter.identity.api.UserPrincipal(7L, "synthetic@example.test",
                cc.ataglace.molebutter.identity.api.UserRole.ADMIN);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null,
                        java.util.List.of()));
        try {
            failures.record(new MockHttpServletRequest("GET", "/api/coupang/products"), "read failure");
            verifyNoInteractions(notices);
            failures.record(new MockHttpServletRequest("PUT", "/api/coupang/working-copies/42"), "입력 보존");
            verify(notices).publish(anyString(), eq("OPERATION_FAILED"), eq("ERROR"), anyString(), eq("입력 보존"),
                    eq("COUPANG"), isNull(), eq(java.util.List.of(7L)));
            failures.record(new MockHttpServletRequest("POST", "/api/coupang/orders/collections"), "수집 요청 실패");
            verify(notices).publish(anyString(), eq("OPERATION_FAILED"), eq("ERROR"), anyString(), eq("수집 요청 실패"),
                    eq("COUPANG_ORDERS"), isNull(), eq(java.util.List.of(7L)));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
