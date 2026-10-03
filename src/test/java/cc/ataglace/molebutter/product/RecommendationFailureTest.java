package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.service.product.*;

class RecommendationFailureTest {
    final Preferences prefs=new Preferences(1,List.of(),List.of(new Rule("1",Mall.LFMALL,null,0)),Map.of(Mall.LFMALL,false));
    Offer offer(Mall mall,String id,long price){return new Offer("NV"+id,"상품",mall.getDisplayName(),id,switch(mall){case LOTTE_IMALL->"https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no="+id;case HI_THEHYUNDAI->"https://hi.thehyundai.com/product/"+id;default->"https://www.lfmall.co.kr/app/product/"+id;},price,0L,mall,null);}
    final Offer selected=offer(Mall.HI_THEHYUNDAI,"selected",166880);
    ProductRefreshService.Work work(long run,long product){return new ProductRefreshService.Work(run,product,0,"query","code","GENERAL",null,prefs,Map.of(),new SelectionBasis("1",SupplierStorePolicy.listingKey(selected),null,"change"));}
    static class Gateway implements ProductSourceGateway {
        List<String> calls=new ArrayList<>();Map<String,RuntimeException> errors=new HashMap<>();
        public String validateUrl(Mall mall,String url){return url;}
        public List<SourceOption> options(Mall mall,String id,String url){return inspect(mall,id,url).options();}
        public SourceDetails inspect(Mall mall,String id,String url){calls.add(id);if(errors.containsKey(id))throw errors.get(id);return new SourceDetails("title","model","",List.of(new SourceOption("FREE","FREE",50L,"AVAILABLE")),mall==Mall.HI_THEHYUNDAI?"목동점":null);}
    }
    @Test void restrictionPreservesSelectedStockAndContinuesOtherMallAndNextProduct(){
        var g=new Gateway();g.errors.put("blocked",new SupplierAccessRestricted(Mall.LOTTE_IMALL,"HTTP_RESTRICTED",403));
        var l=new ProductLookupService(g,new ProductTime());
        var found=new SearchResult(List.of(selected,offer(Mall.LFMALL,"preferred",168970),offer(Mall.LOTTE_IMALL,"blocked",150190),offer(Mall.LOTTE_IMALL,"skip",150200),offer(Mall.HI_THEHYUNDAI,"alternative",155000)),true,null);
        var r=l.lookup(work(1,2),found,()->true);
        assertThat(r.status()).isEqualTo("SUCCESS");assertThat(r.message()).isNull();
        assertThat(g.calls).containsExactly("selected","preferred","blocked","alternative");
        assertThat(r.suppliers()).filteredOn(s->s.offer().mallProductId().equals("selected")).singleElement().satisfies(s->{assertThat(s.offer().price()).isEqualTo(166880);assertThat(s.options().getFirst().stock()).isEqualTo(50);});
        assertThat(r.recommendationDiagnostics()).extracting(RecommendationDiagnostic::kind).containsExactly("FAILED","SKIPPED");
        assertThat(r.recommendationDiagnostics().getFirst().httpStatus()).isEqualTo(403);
        l.lookup(work(1,3),new SearchResult(List.of(selected,offer(Mall.LOTTE_IMALL,"next",150190)),true,null),()->true);
        assertThat(g.calls).doesNotContain("next");
        l.lookup(work(2,3),found,()->true);assertThat(Collections.frequency(g.calls,"blocked")).isEqualTo(2);
    }
    @Test void ordinaryFailureContinuesSameMallAndDoesNotDowngradeStatus(){
        var g=new Gateway();g.errors.put("first",new SupplierLookupFailure(SupplierLookupFailure.Code.TIMEOUT,"FETCH",null,null));
        var r=new ProductLookupService(g,new ProductTime()).lookup(work(1,2),new SearchResult(List.of(selected,offer(Mall.LOTTE_IMALL,"first",150190),offer(Mall.LOTTE_IMALL,"second",150200)),true,null),()->true);
        assertThat(g.calls).containsExactly("selected","first","second");assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(r.recommendationDiagnostics()).singleElement().satisfies(d->assertThat(d.causeCode()).isEqualTo("TIMEOUT"));
    }
    @Test void requiredRequestsStillBlockAndKnownRestrictionPreventsRequiredRequest(){
        var g=new Gateway();g.errors.put("blocked",new SupplierAccessRestricted(Mall.LOTTE_IMALL,"SECURITY_CHECK",null));
        var l=new ProductLookupService(g,new ProductTime());var blocked=offer(Mall.LOTTE_IMALL,"blocked",150190);
        l.lookup(work(1,2),new SearchResult(List.of(selected,blocked),true,null),()->true);
        var w=new ProductRefreshService.Work(1,3,0,"query","code","GENERAL",null,prefs,Map.of(),new SelectionBasis("2",SupplierStorePolicy.listingKey(blocked),null,"change"));
        assertThatThrownBy(()->l.lookup(w,new SearchResult(List.of(blocked),true,null),()->true)).isInstanceOf(SupplierAccessRestricted.class);
        assertThat(Collections.frequency(g.calls,"blocked")).isEqualTo(1);
        g.errors.put("preferred",new SupplierAccessRestricted(Mall.LFMALL,"HTTP_RESTRICTED",429));
        assertThatThrownBy(()->l.lookup(work(2,2),new SearchResult(List.of(selected,offer(Mall.LFMALL,"preferred",168970)),true,null),()->true)).isInstanceOf(SupplierAccessRestricted.class);
    }
    @Test void naverBrowserRestrictionAndLostOwnershipAreNeverSwallowed(){
        var g=new Gateway();g.errors.put("other",new NaverPriceSearch.SearchBlocked(NaverPriceSearch.BlockReason.LOGIN_REQUIRED,"login"));
        var l=new ProductLookupService(g,new ProductTime());var found=new SearchResult(List.of(selected,offer(Mall.LOTTE_IMALL,"other",150190)),true,null);
        assertThatThrownBy(()->l.lookup(work(1,2),found,()->true)).isInstanceOf(NaverPriceSearch.SearchBlocked.class);
        assertThatThrownBy(()->l.lookup(work(1,2),found,()->false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("소유권");
    }
    @Test void skippedRestrictionsDoNotConsumeFortyRequestBudget(){
        var g=new Gateway();g.errors.put("blocked",new SupplierAccessRestricted(Mall.LOTTE_IMALL,"HTTP_RESTRICTED",418));
        var offers=new ArrayList<Offer>();offers.add(selected);offers.add(offer(Mall.LOTTE_IMALL,"blocked",10000));
        for(int i=0;i<60;i++)offers.add(offer(Mall.LOTTE_IMALL,"skip"+i,10001+i));
        for(int i=0;i<40;i++)offers.add(offer(Mall.HI_THEHYUNDAI,"other"+i,11000+i));
        var result=new ProductLookupService(g,new ProductTime()).lookup(work(1,2),new SearchResult(offers,true,null),()->true);
        assertThat(g.calls).hasSize(41);assertThat(result.recommendationLimited()).isTrue();
        assertThat(result.recommendationDiagnostics()).hasSize(61);
    }
    @Test void oldJsonAndDiagnosticRoundTrip(){
        var json=new tools.jackson.databind.ObjectMapper();
        var old=json.readValue("{\"status\":\"SUCCESS\",\"suppliers\":[]}",RefreshResult.class);assertThat(old.recommendationDiagnostics()).isEmpty();
        var d=new RecommendationDiagnostic("1","2","key",Mall.LOTTE_IMALL,"42",150190L,"https://www.lotteimall.com/","FAILED","HTTP_RESTRICTED",403,java.time.LocalDateTime.now());
        assertThat(json.readValue(json.writeValueAsString(d),RecommendationDiagnostic.class)).isEqualTo(d);
    }
}
