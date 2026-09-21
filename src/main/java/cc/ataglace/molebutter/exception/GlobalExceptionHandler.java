package cc.ataglace.molebutter.exception;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import cc.ataglace.molebutter.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 로직 실행 중 발생하는 예상된 예외 처리.
     * 예상된 사용자 실수(4xx)는 INFO로 남긴다 — WARN 이상(molebutter-error.log)은
     * 운영자가 봐야 할 이상 신호만 남기기 위해서다. 발생 이력은 INFO 파일과 DB 감사 로그로 추적한다.
     */
    @ExceptionHandler(BusinessException.class)
    protected ResponseEntity<ApiResponse<Void>> handleBusinessException(
            final BusinessException e,
            HttpServletRequest request) {
        log.info("BusinessException request={} message={}", requestDescription(request), e.getMessage());
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode));
    }

    /**
     * @Valid, @Validated 등으로 인한 바인딩 에러 처리 (주로 클라이언트의 잘못된 입력)
     */
    @ExceptionHandler(BindException.class)
    protected ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        log.info("BindException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e) {
        log.info("MethodArgumentNotValidException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE));
    }

    /**
     * 요청 본문 파싱 실패(깨진 JSON, 본문 없음 등) — 클라이언트 실수이므로 400/INFO.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e) {
        log.info("HttpMessageNotReadableException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    protected ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        log.info("ConstraintViolationException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    protected ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailureException(
            ObjectOptimisticLockingFailureException e) {
        log.warn("ObjectOptimisticLockingFailureException : {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.OPTIMISTIC_LOCKING_FAILURE.getStatus())
                .body(ApiResponse.error(ErrorCode.OPTIMISTIC_LOCKING_FAILURE));
    }

    /**
     * 지원하지 않는 HTTP method 호출 시 발생
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    protected ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e) {
        log.warn("HttpRequestMethodNotSupportedException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    protected ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("MaxUploadSizeExceededException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "IMAGE_UPLOAD_SIZE_EXCEEDED",
                        "상세 이미지는 10MB 이하 파일만 업로드할 수 있습니다."));
    }

    /**
     * 서비스 가드 예외(초과매입 후속 잔여 — "가드 예외 메시지 500 삼킴" 해소).
     * IllegalArgumentException=잘못된 입력(400), IllegalStateException=상태 전이 충돌(409).
     * 메시지를 그대로 내려 화면이 "실제 반품비를 입력하세요" 같은 안내를 보여줄 수 있게 한다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    protected ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("IllegalArgumentException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    protected ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.warn("IllegalStateException : {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(HttpStatus.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    protected ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
        log.warn("ResponseStatusException : {}", e.getReason());
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        HttpStatus responseStatus = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        return ResponseEntity
                .status(responseStatus)
                .body(ApiResponse.error(responseStatus, e.getReason()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    protected void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        log.debug("Client aborted response before it could be written: {}", e.getMessage());
    }

    /**
     * 매핑되지 않은 URL. 스프링이 정적 리소스 미발견(NoResourceFoundException)이나
     * 핸들러 미발견(NoHandlerFoundException)으로 올리는데, catch-all에 흘러가면 500으로 오인되므로 404로 명시한다.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    protected ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e, HttpServletRequest request) {
        log.debug("Not Found: {}", requestDescription(request));
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND));
    }

    /**
     * 그 외 예상치 못한 모든 예외 처리 (서버 내부 오류)
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        if (isClientAbort(e)) {
            log.debug("Client aborted response before it could be written: {}", e.getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Unhandled Exception : {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

        private String requestDescription(HttpServletRequest request) {
        if (request == null) {
            return "-";
        }
        String query = request.getQueryString();
        String uri = query == null || query.isBlank()
                ? request.getRequestURI()
                : request.getRequestURI() + "?" + query;
        return request.getMethod() + " " + uri;
    }

    private boolean isClientAbort(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if ("ClientAbortException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                // "connection reset"은 다운스트림(쿠팡/외부몰/DB) 호출 실패에서도 나타나므로 제외한다.
                // 이를 client abort로 오인하면 실제 서버 오류가 204로 응답되어 묻힌다(F-WEB1).
                // "broken pipe"/"response not usable"은 응답 쓰기 단계의 클라이언트 중단 신호로 한정한다.
                if (normalized.contains("broken pipe")
                        || normalized.contains("response not usable after response errors")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

}
