package cc.ataglace.molebutter.common.api;


import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage() + ": " + cause.getClass().getSimpleName() + " - " + cause.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
