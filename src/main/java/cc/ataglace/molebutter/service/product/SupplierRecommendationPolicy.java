package cc.ataglace.molebutter.service.product;

import java.util.*;
import cc.ataglace.molebutter.infra.product.NaverChannelPolicy;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;

/** Collection and selection share the same evidence, price and stock rules. */
public final class SupplierRecommendationPolicy {
    public static final long MIN_SAVING=1000;
    public static final int MAX_ADDITIONAL_DETAILS=40;
    private SupplierRecommendationPolicy() {}
    public static boolean cheaper(Long price,Long reference){return price!=null&&price>0&&reference!=null&&reference>=MIN_SAVING&&price<=reference-MIN_SAVING;}
    public static boolean soldOut(SupplierResult s){return "CONFIRMED".equals(s.state())&&!s.options().isEmpty()&&s.options().stream().allMatch(o->Set.of("SOLD_OUT","UNAVAILABLE").contains(o.state()));}
    public static Store store(Preferences p,Map<String,String> manual,SupplierResult s){
        if(p==null)return null;
        String id=manual.get(SupplierStorePolicy.listingKey(s.offer()));
        if(id!=null)return p.stores().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);
        return SupplierStorePolicy.resolve(p.stores(),s.offer().mall(),SupplierStorePolicy.identity(s.offer(),s.branch()));
    }
    public static boolean verified(Preferences p,Map<String,String> manual,SupplierResult s){
        if(!s.accepted()||s.offer().mall()==null||!NaverChannelPolicy.comparable(s.offer()))return false;
        if(p==null||!p.branchRequired(s.offer().mall()))return true;
        var identity=SupplierStorePolicy.identity(s.offer(),s.branch());
        if(s.branch()!=null&&"CONFLICT".equals(s.branch().state())||SupplierStorePolicy.conflicts(p.stores(),s.offer().mall(),identity))return false;
        var store=store(p,manual,s);
        if(SupplierStorePolicy.storeContradiction(store,s.branch()))return false;
        return identity!=null||store!=null&&manual.containsKey(SupplierStorePolicy.listingKey(s.offer()));
    }
    public static boolean preferred(Preferences p,Map<String,String> manual,SupplierResult s){
        if(p==null)return true;var store=store(p,manual,s);return p.allowed(s.offer().mall(),store==null?null:store.id());
    }
    public static boolean permittedSeller(ProcurementMall mall,Store store,SupplierResult s){
        var identity=SupplierStorePolicy.identity(s.offer(),s.branch());
        if(mall==ProcurementMall.NAVER_SMART_STORE)return NaverChannelPolicy.comparable(s.offer())&&identity!=null&&("BRANCH".equals(identity.kind())||store!=null&&"BRAND_STORE".equals(store.kind())&&!SupplierStorePolicy.storeContradiction(store,s.branch()));
        if(mall==ProcurementMall.LOTTE_ON)return identity!=null&&("BRANCH".equals(identity.kind())||"COMPANY".equals(identity.kind())&&"company:lf".equals(identity.key()));
        return true;
    }
    public static boolean eligible(Listing l,Long reference){
        return !l.selected()&&!l.preferred()&&!l.conflict()&&l.current()&&"CONFIRMED".equals(l.priceStatus())&&!l.requiresReview()
            &&cheaper(l.result().offer().price(),reference)&&!soldOut(l.result())&&permittedSeller(l.mall(),l.store(),l.result());
    }
}
