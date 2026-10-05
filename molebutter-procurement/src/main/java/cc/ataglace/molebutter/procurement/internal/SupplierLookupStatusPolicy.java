package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;
import cc.ataglace.molebutter.procurement.internal.SupplierStorePolicy;
import cc.ataglace.molebutter.procurement.internal.SupplierRecommendationPolicy;
import cc.ataglace.molebutter.procurement.internal.SupplierGroupStockPolicy;

import java.util.*;



/** Product health is separate from job control and actual option availability. */
public final class SupplierLookupStatusPolicy {
    private SupplierLookupStatusPolicy() {}
    public static final int VERSION=2;
    public record Assessment(String status,List<String> reasons) {}
    public static boolean target(SupplierResult s,Preferences p,Map<String,String> manual,SelectionBasis selection){
        if(selection!=null&&selection.matches(s.offer()))return true;
        if(SupplierRecommendationPolicy.verified(p,manual,s)&&SupplierRecommendationPolicy.preferred(p,manual,s))return true;
        return Set.of("FAILED","GROUP_UNCONFIRMED").contains(s.state())&&(p==null||p.mallAllowed(s.offer().mall()))
            &&SupplierStorePolicy.include(p,manual,s.offer(),s.branch());
    }
    public static boolean closed(SourceOption o){return "UNAVAILABLE".equals(o.state())||Objects.equals(o.stock(),0L)&&!"STOCK_UNKNOWN".equals(o.state());}
    public static Assessment assess(List<SupplierResult> targets,boolean searchNormal){
        if(!searchNormal)return new Assessment("PARTIAL",List.of("SEARCH_UNCONFIRMED"));
        if(targets.isEmpty())return new Assessment("SOLD_OUT",List.of("NO_TARGET_LISTINGS"));
        var reasons=new LinkedHashSet<String>();
        for(var s:targets){
            if(s.skipped()){
                var proof=s.stockEvidence();
                if(proof!=null&&targets.stream().noneMatch(r->!r.skipped()&&SupplierStorePolicy.listingKey(r.offer()).equals(proof.representativeKey())&&SupplierGroupStockPolicy.available(r)&&SupplierGroupStockPolicy.verified(r)&&Objects.equals(SupplierGroupStockPolicy.key(r.offer()),SupplierGroupStockPolicy.key(s.offer()))))reasons.add("STOCK_UNCONFIRMED");
                continue;
            }
            if("FAILED".equals(s.state())){reasons.add("STOCK_LOOKUP_FAILED");continue;}
            if(!s.accepted()||!"CONFIRMED".equals(s.state())||s.options().isEmpty())reasons.add("STOCK_UNCONFIRMED");
            if(s.offer().price()==null||s.offer().price()<=0)reasons.add("PRICE_UNCONFIRMED");
            if(s.offer().deliveryFee()==null)reasons.add("DELIVERY_UNCONFIRMED");
            if(s.options().stream().anyMatch(o->!closed(o)&&(o.stock()==null||o.stock()<0||!"AVAILABLE".equals(o.state()))))reasons.add("STOCK_UNCONFIRMED");
        }
        if(!reasons.isEmpty())return new Assessment("PARTIAL",List.copyOf(reasons));
        if(targets.stream().allMatch(s->!s.skipped()&&!s.options().isEmpty()&&s.options().stream().allMatch(SupplierLookupStatusPolicy::closed)))
            return new Assessment("SOLD_OUT",List.of("ALL_UNAVAILABLE"));
        // A skipped row is normal only while its positive representative remains in this result.
        boolean positive=targets.stream().filter(s->!s.skipped()).flatMap(s->s.options().stream()).anyMatch(o->"AVAILABLE".equals(o.state())&&o.stock()!=null&&o.stock()>0);
        return positive?new Assessment("SUCCESS",List.of()):new Assessment("PARTIAL",List.of("STOCK_UNCONFIRMED"));
    }
    public static String display(String status,String blockReason){
        if(status==null)return "NOT_CHECKED";
        return switch(status){case "NO_MATCH"->"SOLD_OUT";case "STALE"->"NOT_CHECKED";
            case "BLOCKED"->"SUPPLIER_ACCESS_RESTRICTED".equals(blockReason)?"PARTIAL":"FAILED";default->status;};
    }
}
