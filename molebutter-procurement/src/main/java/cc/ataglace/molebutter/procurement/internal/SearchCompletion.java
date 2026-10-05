package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupService;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupStatusPolicy;
import cc.ataglace.molebutter.procurement.internal.SupplierRecommendationPolicy;

import java.time.LocalDateTime;
import java.util.*;

import cc.ataglace.molebutter.procurement.api.SupplierDtos.Preferences;

/** Only actual search/stock problems affect the result; the normal search range needs no notice. */
public final class SearchCompletion {
    private SearchCompletion() {}
    public static boolean normal(String reason){return "COMPLETED".equals(cc.ataglace.molebutter.procurement.api.ProductDtos.normalSearchReason(reason));}
    public static String reason(SearchResult search){return search!=null&&(search.complete()||normal(search.completionReason()))?"COMPLETED":null;}
    public static RefreshResult summarize(List<SupplierResult> observations,String reason,LocalDateTime at,
            Preferences preferences,Map<String,String> manual,boolean limited,List<RecommendationDiagnostic> diagnostics){
        return summarize(observations,reason,at,preferences,manual,limited,diagnostics,null);
    }
    public static RefreshResult summarize(List<SupplierResult> observations,String reason,LocalDateTime at,
            Preferences preferences,Map<String,String> manual,boolean limited,List<RecommendationDiagnostic> diagnostics,
            cc.ataglace.molebutter.procurement.api.SupplierDtos.SelectionBasis selection){
        var targets=observations.stream().filter(r->SupplierLookupStatusPolicy.target(r,preferences,manual,selection)).toList();
        var summary=SupplierLookupService.summarize(targets,normal(reason),at);
        var assessment=SupplierLookupStatusPolicy.assess(targets,normal(reason));
        if(targets.stream().anyMatch(r->!"FAILED".equals(r.state())&&!SupplierRecommendationPolicy.verified(preferences,manual,r))){
            var issues=new ArrayList<>(assessment.reasons());issues.remove("ALL_UNAVAILABLE");issues.add("STORE_UNCONFIRMED");assessment=new SupplierLookupStatusPolicy.Assessment("PARTIAL",List.copyOf(issues));
        }
        long failures=targets.stream().filter(r->"FAILED".equals(r.state())).count();
        long unknown=targets.stream().filter(r->!r.skipped()&&!"FAILED".equals(r.state())
            &&SupplierRecommendationPolicy.verified(preferences,manual,r)
            &&(r.options().isEmpty()||"OPTIONS_PARTIAL".equals(r.state())||r.options().stream().anyMatch(o->!SupplierLookupStatusPolicy.closed(o)&&(o.stock()==null||"STOCK_UNKNOWN".equals(o.state()))))).count();
        var notices=new ArrayList<String>();
        if(unknown>0)notices.add("재고 미확인 "+unknown+"건");
        if(failures>0)notices.add("조회 실패 "+failures+"건");
        if(!normal(reason))notices.add("검색 일부 조회");
        if(limited)notices.add("추천 일부 조회");
        if(assessment.reasons().contains("NO_TARGET_LISTINGS"))notices.add("대상 판매글 없음");
        if(assessment.reasons().contains("ALL_UNAVAILABLE"))notices.add("모두 재고 0·구매 불가");
        if(assessment.reasons().contains("STORE_UNCONFIRMED"))notices.add("선정 매입처 정보 확인 필요");
        if(assessment.reasons().contains("PRICE_UNCONFIRMED"))notices.add("가격 미확인");
        if(assessment.reasons().contains("DELIVERY_UNCONFIRMED"))notices.add("배송비 미확인");
        return new RefreshResult(assessment.status(),summary.searchPrice(),summary.searchMall(),summary.searchDeliveryFee(),observations,at,
            notices.isEmpty()?null:String.join(" · ",notices),limited,diagnostics,reason,SupplierLookupStatusPolicy.VERSION,assessment.reasons());
    }
}
