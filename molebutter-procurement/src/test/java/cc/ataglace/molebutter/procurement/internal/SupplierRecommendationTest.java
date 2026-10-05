package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;

import cc.ataglace.molebutter.common.api.BusinessTime;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;


import cc.ataglace.molebutter.procurement.internal.SupplierProductGateway;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupService;
import cc.ataglace.molebutter.procurement.internal.DefaultSupplierRefreshService;
import cc.ataglace.molebutter.procurement.internal.SupplierStorePolicy;
import cc.ataglace.molebutter.procurement.internal.SupplierRecommendationPolicy;

class SupplierRecommendationTest {
    final Preferences prefs=new Preferences(1,List.of(),List.of(new Rule("1",ProcurementMall.LFMALL,null,0)),Map.of(ProcurementMall.LFMALL,false));
    Offer lf(String id,long price){return new Offer("NV"+id,"ABCD6F123BK","LF몰",id,"https://www.lfmall.co.kr/app/product/"+id,price,0L,ProcurementMall.LFMALL,null);}
    Offer hi(String id,long price){return new Offer("NV"+id,"목동점 ABCD6F123BK","더현대Hi",id,"https://hi.thehyundai.com/product/"+id,price,9000L,ProcurementMall.HI_THEHYUNDAI,null);}
    DefaultSupplierRefreshService.Work work(Offer selected){return new DefaultSupplierRefreshService.Work(1,2,0,"ABCD123","ABCD6F123BK","LF_ACCESSORY","HAZZYS",prefs,Map.of(),new SelectionBasis(selected==null?null:"selected",selected==null?null:SupplierStorePolicy.listingKey(selected),null,"change"));}
    SupplierProductGateway gateway(List<String> calls){return new SupplierProductGateway(){
        public String validateUrl(ProcurementMall m,String url){return url;}
        public List<SourceOption> options(ProcurementMall m,String id,String url){calls.add(id);return List.of(new SourceOption("FREE","FREE",3L,"AVAILABLE"));}
    };}
    @Test void noSelectionSkipsNonPreferredAndMissingBaselineDefersDiscovery(){
        var calls=new ArrayList<String>();var lookup=new SupplierLookupService(gateway(calls),new BusinessTime());
        var selected=lf("s",94000);var search=new SearchResult(List.of(hi("r",90000),lf("p",100000)),true,null);
        assertThat(lookup.lookup(work(null),search,()->true).suppliers()).extracting(s->s.offer().mallProductId()).containsExactly("p");
        assertThat(lookup.lookup(work(selected),search,()->true).suppliers()).extracting(s->s.offer().mallProductId()).containsExactly("p");assertThat(calls).containsExactly("p");
    }
    @Test void selectedThenPreferredThenFortyCheapestAdditionalRequestsAndRunCache(){
        var calls=new ArrayList<String>();var lookup=new SupplierLookupService(gateway(calls),new BusinessTime());var selected=lf("s",94000);
        var offers=new ArrayList<Offer>();for(int i=44;i>=0;i--)offers.add(hi("r"+i,80000+i));offers.add(lf("p",100000));offers.add(selected);offers.add(hi("tooClose",93001));offers.add(hi("threshold",93000));
        var result=lookup.lookup(work(selected),new SearchResult(offers,true,null),()->true);
        assertThat(calls.subList(0,2)).containsExactly("s","p");assertThat(calls).hasSize(42);assertThat(calls.get(2)).isEqualTo("r0");assertThat(calls.getLast()).isEqualTo("r39");assertThat(result.recommendationLimited()).isTrue();
        lookup.lookup(work(selected),new SearchResult(List.of(offers.get(44),selected),true,null),()->true);assertThat(calls).hasSize(42);
    }
    @Test void selectedNonPreferredStillCheckedFirstAndShippingNotUsed(){
        var calls=new ArrayList<String>();var selected=hi("s",94000);
        var result=new SupplierLookupService(gateway(calls),new BusinessTime()).lookup(work(selected),new SearchResult(List.of(hi("r",93000),lf("p",100000),selected,hi("tooClose",93001)),true,null),()->true);
        assertThat(calls).containsExactly("s","p","r");assertThat(result.suppliers()).extracting(s->s.offer().mallProductId()).containsExactly("s","p","r");
    }
    @Test void exactThresholdAndPartialStockDoNotImplySoldOut(){
        assertThat(SupplierRecommendationPolicy.cheaper(93000L,94000L)).isTrue();assertThat(SupplierRecommendationPolicy.cheaper(93001L,94000L)).isFalse();assertThat(SupplierRecommendationPolicy.cheaper(1L,null)).isFalse();
        var option=List.of(new SourceOption("x","FREE",0L,"SOLD_OUT"));var code=new CodeMatch("MATCHED","ABCD6F123BK","ABCD123","6F","BK",null);
        for(String state:List.of("OPTIONS_PARTIAL","FAILED","OPTIONS_UNKNOWN"))assertThat(SupplierRecommendationPolicy.soldOut(new SupplierResult(lf("a",10),code,state,option,null))).isFalse();
        assertThat(SupplierRecommendationPolicy.soldOut(new SupplierResult(lf("a",10),code,"CONFIRMED",option,null))).isTrue();
        assertThat(SupplierRecommendationPolicy.soldOut(new SupplierResult(lf("a",10),code,"CONFIRMED",List.of(new SourceOption("x","FREE",null,"STOCK_UNKNOWN")),null))).isFalse();
    }
    @Test void genericSellerCannotBecomeOfficialByDisplayNameOrManualAssignment(){
        var offer=new Offer("NV","ABCD6F123BK","헤지스","123","https://brand.naver.com/hazzys/products/123",100L,0L,ProcurementMall.NAVER_SMART_STORE,null,new NaverChannel(NaverChannelType.WINDOW,"BRAND_FASHION"));
        var proof=new StoreEvidence("SELLER",null,"헤지스","NAVER_CHANNEL","unregistered");
        var s=new SupplierResult(offer,new CodeMatch("MATCHED","ABCD6F123BK","ABCD123","6F","BK",null),"FAILED",List.of(),null,null,null,null,new BranchInfo("헤지스","CONFIRMED","API",null,proof));
        assertThat(SupplierRecommendationPolicy.permittedSeller(offer.mall(),null,s)).isFalse();
        var official=new Store("1",offer.mall(),"BRAND_STORE","헤지스","external:registered",List.of(),0,null,List.of(new ExternalIdentity("NAVER_CHANNEL","registered")));
        assertThat(SupplierRecommendationPolicy.permittedSeller(offer.mall(),official,s)).isFalse();
        var registered=new Store("1",offer.mall(),"BRAND_STORE","이름 변경","external:unregistered",List.of(),0,null,List.of(new ExternalIdentity("NAVER_CHANNEL","unregistered")));
        assertThat(SupplierRecommendationPolicy.permittedSeller(offer.mall(),registered,s)).isTrue();
    }
}
