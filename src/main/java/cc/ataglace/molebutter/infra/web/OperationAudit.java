package cc.ataglace.molebutter.infra.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationAudit {

    /** 이벤트 종류. 예: COUPANG_PRODUCT_UPDATE, EXCEL_UPLOAD. */
    String value();

    /** 대상 타입(선택). 예: COUPANG_PRODUCT, PRODUCT. */
    String targetType() default "";

    /** targetId로 남길 URI path variable 이름(선택). */
    String targetIdPathVariable() default "";

    /** targetId로 남길 request parameter 이름(선택). path variable보다 후순위다. */
    String targetIdRequestParam() default "";

}
