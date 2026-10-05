package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.internal.SupplierBranch;
import cc.ataglace.molebutter.procurement.internal.SupplierStorePolicy;

import java.util.*;

import cc.ataglace.molebutter.procurement.internal.NaverChannelPolicy;

/** A per-product, per-observation proof. Never a shared inventory cache. */
public final class SupplierGroupStockPolicy {
    private SupplierGroupStockPolicy() {}
    private static String clean(String v){return v==null?"":v.replaceAll("\\s+","");}
    public static String key(Offer o){
        var e=o.searchStore();
        if(o.mall()!=ProcurementMall.NAVER_SMART_STORE||!NaverChannelPolicy.probeAllowed(o)||e==null||e.channelId()==null||!e.channelId().matches("[0-9]+")||!e.channelId().equals(e.cachedChannelId()))return null;
        var titleBranch=SupplierBranch.resolve(o,null);if("CONFLICT".equals(titleBranch.state()))return null;
        if(titleBranch.name()!=null&&!clean(e.storeName()).contains(clean(titleBranch.name())))return null;
        if("1".equals(e.windowType())&&"백화점".equals(e.windowName())&&!clean(e.storeName()).isEmpty())return e.channelId()+":DEPARTMENT:"+clean(e.storeName());
        if(("브랜드패션".equals(e.windowName())||"브랜드직영관".equals(e.windowName())||o.naverChannel()!=null&&"BRAND_FASHION".equals(o.naverChannel().vertical()))&&!clean(e.channelName()).isEmpty())return e.channelId()+":BRAND:"+clean(e.channelName());
        return null;
    }
    public static boolean verified(SupplierResult r){
        var o=r.offer();var b=r.branch();String key=key(o);
        if(key==null||!NaverChannelPolicy.comparable(o)||b==null||b.store()==null||"CONFLICT".equals(b.state()))return false;
        var s=b.store();if(!o.searchStore().channelId().equals(s.references().get("channelId")))return false;
        return "BRANCH".equals(s.kind())?clean(s.retailer()+s.name()).equals(clean(o.searchStore().storeName())):
            "SELLER".equals(s.kind())&&clean(s.name()).equals(clean(o.searchStore().channelName()));
    }
    public static boolean available(SupplierResult r){return !r.skipped()&&Set.of("CONFIRMED","OPTIONS_PARTIAL").contains(r.state())&&r.options().stream().anyMatch(o->o.stock()!=null&&o.stock()>0&&"AVAILABLE".equals(o.state()));}
    public static SupplierResult skip(Offer offer,SupplierResult proof,long run){
        var branch=proof.branch();
        return new SupplierResult(offer.withChannel(proof.offer().naverChannel()),new CodeMatch("SEARCH_RESULT",null,null,null,null,null),"SKIPPED_SAME_STORE",List.of(),"같은 매장의 구매 가능한 판매글을 확인했습니다.",null,null,null,
            new BranchInfo(branch.name(),branch.state(),"SAME_CHANNEL",branch.evidence(),branch.store()),
            new StockEvidence(run,SupplierStorePolicy.listingKey(proof.offer()),offer.searchStore().channelId(),null,false));
    }
}
