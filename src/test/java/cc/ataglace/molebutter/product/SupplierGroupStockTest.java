package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.service.product.*;

class SupplierGroupStockTest {
    final Preferences prefs=new Preferences(1,List.of(),List.of(new Rule("1",Mall.NAVER_SMART_STORE,null,0)));
    Offer offer(String id,long price){return new Offer("nv"+id,"헤지스 가방","헤지스ACC",id,"https://shopping.naver.com/outlink/itemdetail/"+id,price,0L,Mall.NAVER_SMART_STORE,null,NaverChannelPolicy.unknown(),new SearchStoreEvidence("1000008804","1000008804","헤지스ACC","현대백화점 목동점","1","백화점"));}
    ProductRefreshService.Work work(long product,Offer selected){return new ProductRefreshService.Work(1,product,0,"HIBA113K2","HIBA113K2","GENERAL",null,prefs,Map.of(),new SelectionBasis(selected==null?null:"s",selected==null?null:SupplierStorePolicy.listingKey(selected),null,null));}
    SourceDetails details(Long stock){return new SourceDetails("","","",List.of(new SourceOption("FREE","FREE",stock,stock==null?"STOCK_UNKNOWN":stock>0?"AVAILABLE":"SOLD_OUT")),"목동점",new StoreEvidence("BRANCH","현대백화점","목동점","NAVER_DEPARTMENT","10001/10001004",Map.of("channelId","1000008804")),true,new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT"));}
    static class Gateway implements ProductSourceGateway {
        final List<String> calls=new ArrayList<>();final Map<String,SourceDetails> data=new HashMap<>();
        public String validateUrl(Mall m,String u){return u;}public List<SourceOption> options(Mall m,String id,String u){throw new AssertionError();}
        public SourceDetails inspect(Mall m,String id,String u){calls.add(id);if(!data.containsKey(id))throw new IllegalStateException("fixture failure");return data.get(id);}
    }
    @Test void actualMokdongFixtureSkipsSecondWithoutInventingInventoryAndManualIsFresh() throws Exception {
        var gateway=new Gateway();var parser=new MallOptionParser(new ObjectMapper());
        for(String[] f:List.of(new String[]{"13656623827","first"},new String[]{"6617877030","second"}))gateway.data.put(f[0],parser.details(Mall.NAVER_SMART_STORE,Files.readString(Path.of("src/test/resources/product/supplier-group/mokdong-"+f[1]+"-detail.json")),f[0]));
        var first=offer("13656623827",217720);var second=offer("6617877030",217720);var service=new ProductLookupService(gateway,new ProductTime());
        var r=service.lookup(work(2,null),new SearchResult(List.of(second,first),true,null),()->true);
        assertThat(gateway.calls).containsExactly("13656623827");assertThat(r.status()).isEqualTo("SUCCESS");assertThat(r.message()).isNull();
        assertThat(r.suppliers().getFirst().options().getFirst().stock()).isEqualTo(25);var skipped=r.suppliers().getLast();assertThat(skipped.state()).isEqualTo("SKIPPED_SAME_STORE");assertThat(skipped.options()).isEmpty();assertThat(skipped.stockEvidence().checkedAt()).isNull();assertThat(skipped.sourceModelCode()).isNull();
        var manual=service.inspectFresh(work(2,null),second);assertThat(manual.options().getFirst().stock()).isEqualTo(13);assertThat(manual.stockEvidence().directVerified()).isTrue();assertThat(gateway.calls).containsExactly("13656623827","6617877030");
        var json=new ObjectMapper();assertThat(json.readTree(json.writeValueAsString(skipped)).path("stockEvidence").path("runId").isString()).isTrue();assertThat(json.readValue(json.writeValueAsString(skipped),SupplierResult.class)).isEqualTo(skipped);
    }
    @Test void selectedAvailableStillChecksCheaperAlternativeAndSkipsMoreExpensive(){
        var g=new Gateway();for(String id:List.of("1","2","3","4"))g.data.put(id,details(5L));var selected=offer("3",3000);
        var r=new ProductLookupService(g,new ProductTime()).lookup(work(2,selected),new SearchResult(List.of(offer("4",4000),offer("2",2000),selected,offer("1",1000)),true,null),()->true);
        assertThat(g.calls).containsExactly("3","1");assertThat(r.suppliers().stream().filter(SupplierResult::skipped)).hasSize(2);
    }
    @Test void zeroUnknownAndFailureContinueThenStopAtAvailable(){
        for(int kind=0;kind<3;kind++){var g=new Gateway();if(kind<2)g.data.put("1",details(kind==0?0L:null));g.data.put("2",details(7L));g.data.put("3",details(8L));
            var r=new ProductLookupService(g,new ProductTime()).lookup(work(2,null),new SearchResult(List.of(offer("1",1000),offer("2",2000),offer("3",3000)),true,null),()->true);assertThat(g.calls).containsExactly("1","2");assertThat(r.suppliers().getLast().skipped()).isTrue();assertThat(r.status()).isEqualTo(kind==0?"SUCCESS":"PARTIAL");}
    }
    @Test void missingIdentityDifferentChannelAndConflictsNeverShareProof(){
        for(int kind=0;kind<3;kind++){var g=new Gateway();g.data.put("1",details(5L));g.data.put("2",details(6L));var old=offer("2",2000);SearchStoreEvidence evidence=kind==0?null:new SearchStoreEvidence(kind==1?"99":"1000008804","99","헤지스ACC","현대백화점 목동점","1","백화점");
            var second=new Offer(old.naverProductId(),old.title(),old.mallName(),old.mallProductId(),old.url(),old.price(),0L,old.mall(),null,old.naverChannel(),evidence);
            new ProductLookupService(g,new ProductTime()).lookup(work(2,null),new SearchResult(List.of(offer("1",1000),second),true,null),()->true);assertThat(g.calls).containsExactly("1","2");}
    }
    @Test void proofDoesNotLeakToAnotherProductButExactDetailCacheIsReused(){
        var g=new Gateway();g.data.put("1",details(5L));g.data.put("2",details(6L));var service=new ProductLookupService(g,new ProductTime());
        service.lookup(work(2,null),new SearchResult(List.of(offer("1",1000),offer("2",2000)),true,null),()->true);
        service.lookup(work(3,null),new SearchResult(List.of(offer("2",2000)),true,null),()->true);assertThat(g.calls).containsExactly("1","2");
        service.lookup(work(4,null),new SearchResult(List.of(offer("2",2000)),true,null),()->true);assertThat(g.calls).hasSize(2);
    }
    @Test void skippedCandidatesKeepRecommendationPriceThresholdAndDoNotConsumeDetailBudget(){
        var g=new Gateway();g.data.put("1",details(5L));var selected=new Offer("nvS","selected","LF몰","s","https://www.lfmall.co.kr/app/product/s",5000L,0L,Mall.LFMALL,null);g.data.put("s",details(5L));
        var offers=new ArrayList<Offer>();offers.add(selected);for(int i=1;i<=45;i++)offers.add(offer(Integer.toString(i),3000+i));
        var otherPrefs=new Preferences(1,List.of(),List.of(new Rule("1",Mall.LFMALL,null,0)),Map.of(Mall.LFMALL,false));
        var w=new ProductRefreshService.Work(1,2,0,"q","code","GENERAL",null,otherPrefs,Map.of(),new SelectionBasis("s",SupplierStorePolicy.listingKey(selected),null,null));
        var r=new ProductLookupService(g,new ProductTime()).lookup(w,new SearchResult(offers,true,null),()->true);assertThat(g.calls).containsExactly("s","1");assertThat(r.recommendationLimited()).isFalse();assertThat(r.suppliers()).hasSize(46);
    }
    @Test void cacheReusesOriginalStockTimestampAndManualInvalidatesOnlyThatEntry(){
        class Clock extends ProductTime {java.time.LocalDateTime now=java.time.LocalDateTime.parse("2026-09-28T10:00:00");@Override public java.time.LocalDateTime now(){return now;}}
        var clock=new Clock();var g=new Gateway();g.data.put("1",details(5L));var service=new ProductLookupService(g,clock);var query=new SearchResult(List.of(offer("1",1000)),true,null);
        var first=service.lookup(work(2,null),query,()->true);var original=first.suppliers().getFirst().stockEvidence().checkedAt();clock.now=clock.now.plusHours(1);
        var second=service.lookup(work(3,null),query,()->true);assertThat(second.suppliers().getFirst().stockEvidence().checkedAt()).isEqualTo(original);assertThat(g.calls).hasSize(1);
        service.inspectFresh(work(3,null),offer("1",1000));service.invalidate(Mall.NAVER_SMART_STORE,"1");
        var third=service.lookup(work(4,null),query,()->true);assertThat(third.suppliers().getFirst().stockEvidence().checkedAt()).isEqualTo(clock.now);assertThat(g.calls).hasSize(3);
    }
}
