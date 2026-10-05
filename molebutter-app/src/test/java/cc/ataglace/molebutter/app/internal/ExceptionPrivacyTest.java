package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.app.internal.GlobalExceptionHandler;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.OperationFailure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.*;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import cc.ataglace.molebutter.app.internal.FailureNotificationService;

class ExceptionPrivacyTest {
    @Test void exceptionLogsOmitRejectedValuesCausesAndQueryWhileKeepingBusinessResponses() {
        String secret="SYNTHETIC_SECRET_NOT_FOR_LOGS";
        var notifications=mock(FailureNotificationService.class);
        doThrow(new RuntimeException(secret)).when(notifications).record(any(),anyString());
        var handler=new GlobalExceptionHandler(notifications);
        var logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var originalLevel=logger.getLevel();
        var output=new ListAppender<ILoggingEvent>();output.start();logger.addAppender(output);
        try {
            logger.setLevel(Level.INFO);
            var binding=new BeanPropertyBindingResult(new Object(),"request");
            binding.addError(new FieldError("request","credential",secret,false,null,null,"invalid"));
            int captured=output.list.size();
            assertThat(handler.handleBindException(new BindException(binding)).getStatusCode().value()).isEqualTo(400);
            assertThat(output.list.subList(captured,output.list.size())).anyMatch(e->e.getLevel()==Level.INFO);
            captured=output.list.size();
            assertThat(handler.handleIllegalArgumentException(new IllegalArgumentException(secret)).getStatusCode().value()).isEqualTo(400);
            assertThat(output.list.subList(captured,output.list.size())).anyMatch(e->e.getLevel()==Level.INFO);
            var request=new MockHttpServletRequest("POST","/api/inventory/purchases");request.setQueryString("secret="+secret);
            assertThat(handler.handleOperationFailure(new OperationFailure("버전이 변경되었습니다."),request).getStatusCode().value()).isEqualTo(409);
            var response=handler.handleException(new RuntimeException(secret,new RuntimeException(secret)),request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().getMessage()).doesNotContain(secret);
            assertThat(output.list).isNotEmpty().allSatisfy(e->{
                assertThat(e.getFormattedMessage()).doesNotContain(secret);
                assertThat(e.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(output);output.stop();logger.setLevel(originalLevel); }
    }
    @Test void validationAndUnexpectedFailuresDoNotExposeRejectedValuesCausesOrQueryStrings() {
        String secret="SYNTHETIC_SECRET_NOT_FOR_LOGS";
        var handler=new GlobalExceptionHandler(mock(FailureNotificationService.class));
        var logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var originalLevel=logger.getLevel();
        var output=new ListAppender<ILoggingEvent>();output.start();logger.addAppender(output);
        try {
            logger.setLevel(Level.INFO);
            var binding=new BeanPropertyBindingResult(new Object(),"request");
            binding.addError(new FieldError("request","credential",secret,false,null,null,"invalid"));
            int captured=output.list.size();
            assertThat(handler.handleBindException(new BindException(binding)).getBody().getMessage()).doesNotContain(secret);
            assertThat(output.list.subList(captured,output.list.size())).anyMatch(e->e.getLevel()==Level.INFO);
            captured=output.list.size();
            assertThat(handler.handleIllegalArgumentException(new IllegalArgumentException(secret)).getBody().getMessage()).doesNotContain(secret);
            assertThat(output.list.subList(captured,output.list.size())).anyMatch(e->e.getLevel()==Level.INFO);
            var request=new MockHttpServletRequest("POST","/api/inventory/purchases");request.setQueryString("secret="+secret);
            var response=handler.handleIllegalStateException(new IllegalStateException(secret,new RuntimeException(secret)),request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);assertThat(response.getBody().getMessage()).doesNotContain(secret);
            var status=handler.handleResponseStatusException(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,secret));
            assertThat(status.getStatusCode().value()).isEqualTo(502);
            assertThat(status.getBody().getMessage()).doesNotContain(secret);
            assertThat(output.list).isNotEmpty().allSatisfy(e->{assertThat(e.getFormattedMessage()).doesNotContain(secret);assertThat(e.getThrowableProxy()).isNull();});
        } finally { logger.detachAppender(output);output.stop();logger.setLevel(originalLevel); }
    }
    @Test void authoredInputAndStateExplanationsKeepTheirBusinessStatus() {
        var handler=new GlobalExceptionHandler(mock(FailureNotificationService.class));
        var input=handler.handleInputValidationFailure(new InputValidationFailure("수량을 확인해 주세요."));
        assertThat(input.getStatusCode().value()).isEqualTo(400);
        assertThat(input.getBody().getMessage()).isEqualTo("수량을 확인해 주세요.");
        var conflict=handler.handleOperationFailure(new OperationFailure("버전이 변경되었습니다."),new MockHttpServletRequest());
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().getMessage()).isEqualTo("버전이 변경되었습니다.");
    }
}
