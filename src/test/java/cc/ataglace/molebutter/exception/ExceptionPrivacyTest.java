package cc.ataglace.molebutter.exception;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.*;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import cc.ataglace.molebutter.service.notification.FailureNotificationService;

class ExceptionPrivacyTest {
    @Test void exceptionLogsOmitRejectedValuesCausesAndQueryWhileKeepingBusinessResponses() {
        String secret="SYNTHETIC_SECRET_NOT_FOR_LOGS";
        var notifications=mock(FailureNotificationService.class);
        doThrow(new RuntimeException(secret)).when(notifications).record(any(),anyString());
        var handler=new GlobalExceptionHandler(notifications);
        var logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var output=new ListAppender<ILoggingEvent>();output.start();logger.addAppender(output);
        try {
            var binding=new BeanPropertyBindingResult(new Object(),"request");
            binding.addError(new FieldError("request","credential",secret,false,null,null,"invalid"));
            assertThat(handler.handleBindException(new BindException(binding)).getStatusCode().value()).isEqualTo(400);
            assertThat(handler.handleIllegalArgumentException(new IllegalArgumentException(secret)).getStatusCode().value()).isEqualTo(400);
            var request=new MockHttpServletRequest("POST","/api/inventory/purchases");request.setQueryString("secret="+secret);
            assertThat(handler.handleIllegalStateException(new OperationFailure("버전이 변경되었습니다."),request).getStatusCode().value()).isEqualTo(409);
            var response=handler.handleException(new RuntimeException(secret,new RuntimeException(secret)),request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().getMessage()).doesNotContain(secret);
            assertThat(output.list).isNotEmpty().allSatisfy(e->{
                assertThat(e.getFormattedMessage()).doesNotContain(secret);
                assertThat(e.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(output);output.stop(); }
    }
}
