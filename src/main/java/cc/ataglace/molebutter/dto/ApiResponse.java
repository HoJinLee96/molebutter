package cc.ataglace.molebutter.dto;

import org.springframework.http.HttpStatus;

import cc.ataglace.molebutter.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponse<T> {

    private final int status;
    private final String code;
    private final String message;
    private final T data;

    // 성공 응답 (데이터 있음)
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(HttpStatus.OK.value(), "SUCCESS", "성공", data);
    }

    // 성공 응답 (데이터 없음)
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(HttpStatus.OK.value(), "SUCCESS", "성공", null);
    }

    // 생성 응답 (데이터 있음)
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(HttpStatus.CREATED.value(), "CREATED", "생성 성공", data);
    }

    // 에러 응답 (비즈니스 예외 - ErrorCode 기반)
    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getStatus().value(), errorCode.name(), errorCode.getMessage(), null);
    }

    public static ApiResponse<Void> error(HttpStatus status, String message) {
        return new ApiResponse<>(status.value(), status.name(), message, null);
    }

    // 에러 응답 (직접 메시지 지정)
    public static ApiResponse<Void> error(HttpStatus status, String code, String message) {
        return new ApiResponse<>(status.value(), code, message, null);
    }
}
