package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.internal.NaverPriceSearch;

import cc.ataglace.molebutter.procurement.api.ProductDtos.ProcurementMall;

/** Typed supplier restriction, distinct from the browser's Naver search restrictions. */
public final class SupplierAccessRestricted extends NaverPriceSearch.SearchBlocked {
    private final ProcurementMall mall;
    private final String code;
    private final Integer httpStatus;
    public SupplierAccessRestricted(ProcurementMall mall,String code,Integer httpStatus){
        super("[쇼핑몰 재고 조회 제한] "+mall.getDisplayName()+" · "+(httpStatus!=null?"HTTP "+httpStatus:"보안 확인")+". 잠시 후 재개해 주세요.");
        this.mall=mall;this.code=code;this.httpStatus=httpStatus;
    }
    public ProcurementMall mall(){return mall;}
    public String code(){return code;}
    public Integer httpStatus(){return httpStatus;}
}
