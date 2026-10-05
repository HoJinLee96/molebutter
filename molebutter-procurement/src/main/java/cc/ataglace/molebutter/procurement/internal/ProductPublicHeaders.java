package cc.ataglace.molebutter.procurement.internal;


/** 검색 전용 비로그인 Chrome의 공개 User-Agent만 사용한다. 쿠키·인증 값은 공유하지 않는다. */
public final class ProductPublicHeaders {
    private ProductPublicHeaders() {}
    private static volatile String browserUserAgent="Mozilla/5.0";
    public static String userAgent(){return browserUserAgent;}
    public static void setBrowserUserAgent(String value){if(value!=null&&!value.isBlank()&&value.length()<512&&!value.contains("\r")&&!value.contains("\n"))browserUserAgent=value;}
}
