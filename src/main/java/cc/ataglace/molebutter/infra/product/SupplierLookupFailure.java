package cc.ataglace.molebutter.infra.product;

/** 외부 응답이나 예외 메시지를 노출하지 않는 조회 실패 분류. */
public final class SupplierLookupFailure extends IllegalStateException {
    public enum Code { TIMEOUT, NETWORK, HTTP, RESPONSE_FORMAT, PRODUCT_MISMATCH, INTERNAL }
    private final Code code;
    private final String stage;
    private final Integer httpStatus;
    public SupplierLookupFailure(Code code,String stage,Integer httpStatus,Throwable cause){
        super(message(code,httpStatus),cause);this.code=code;this.stage=stage;this.httpStatus=httpStatus;
    }
    public Code code(){return code;}
    public String stage(){return stage;}
    public Integer httpStatus(){return httpStatus;}
    public static SupplierLookupFailure classify(Exception error){
        if(error instanceof SupplierLookupFailure failure)return failure;
        return new SupplierLookupFailure(Code.INTERNAL,"PROCESS",null,error);
    }
    private static String message(Code code,Integer status){return switch(code){
        case TIMEOUT -> "재고 조회 시간 초과";
        case NETWORK -> "재고 조회 통신 오류";
        case HTTP -> "재고 조회 실패 · HTTP "+status;
        case RESPONSE_FORMAT -> "상품 응답 형식 오류";
        case PRODUCT_MISMATCH -> "상품 응답 불일치";
        case INTERNAL -> "재고 조회 처리 오류";
    };}
}
