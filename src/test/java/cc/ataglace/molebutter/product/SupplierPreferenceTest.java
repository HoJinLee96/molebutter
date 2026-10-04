package cc.ataglace.molebutter.product;
import cc.ataglace.molebutter.service.common.BusinessTime;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.service.product.*;
import cc.ataglace.molebutter.infra.product.*;

class SupplierPreferenceTest {
    Store branch=new Store("1",ProcurementMall.HI_THEHYUNDAI,"BRANCH","목동점","branch:목동점",List.of("목동점"),0);
    Preferences specific=new Preferences(1,List.of(branch),List.of(new Rule("2",ProcurementMall.HI_THEHYUNDAI,"1",0)));
    Offer offer(ProcurementMall mall,String id,String title){return new Offer("NV"+id,title,mall.getDisplayName(),id,(mall==ProcurementMall.LFMALL?"https://www.lfmall.co.kr/app/product/":"https://hi.thehyundai.com/product/")+id,100L,0L,mall,null);}
    BranchInfo branch(String name){return new BranchInfo(name,name.isBlank()?"UNKNOWN":"CONFIRMED","TITLE",name);}
    @Test void wholeMallSpecificBranchUnknownAndManualOverrideAreDistinct(){var o=offer(ProcurementMall.HI_THEHYUNDAI,"a","ABCD123");assertThat(SupplierStorePolicy.include(specific,Map.of(),o,branch("목동점"))).isTrue();assertThat(SupplierStorePolicy.include(specific,Map.of(),o,branch("천호점"))).isFalse();assertThat(SupplierStorePolicy.include(specific,Map.of(),o,branch(""))).isTrue();assertThat(SupplierStorePolicy.include(specific,Map.of(SupplierStorePolicy.listingKey(o),"1"),o,branch("천호점"))).isTrue();assertThat(SupplierStorePolicy.include(specific,Map.of(),offer(ProcurementMall.LFMALL,"b","ABCD123"),branch("목동점"))).isFalse();var whole=new Preferences(1,List.of(),List.of(new Rule("2",ProcurementMall.HI_THEHYUNDAI,null,0)));assertThat(SupplierStorePolicy.include(whole,Map.of(),o,branch("천호점"))).isTrue();}
    @Test void smartstoreUrlAloneDoesNotProveDepartmentStoreOrGeneralSeller(){
        var one=smart("10");var two=smart("11");
        assertThat(SupplierStorePolicy.identity(one,branch(""))).isNull();
        assertThat(SupplierStorePolicy.identity(two,branch("목동점"))).isNull();
        assertThat(SupplierStorePolicy.listingKey(one)).isNotEqualTo(SupplierStorePolicy.listingKey(two));
    }
    @Test void filtersBeforeSupplierCallsAndKeepsUnknownForReview(){List<String> calls=new ArrayList<>();SupplierProductGateway gateway=new SupplierProductGateway(){public String validateUrl(ProcurementMall mall,String url){return url;} public List<SourceOption> options(ProcurementMall mall,String id,String url){calls.add(id);return List.of(new SourceOption("FREE","FREE",1L,"AVAILABLE"));}};var work=new SupplierRefreshService.Work(1,2,0,"ABCD123","ABCD6F123BK","LF_ACCESSORY","HAZZYS",specific,Map.of());var result=new SupplierLookupService(gateway,new BusinessTime()).lookup(work,new SearchResult(List.of(offer(ProcurementMall.LFMALL,"skipMall","ABCD123"),offer(ProcurementMall.HI_THEHYUNDAI,"skipBranch","천호점 ABCD123"),offer(ProcurementMall.HI_THEHYUNDAI,"keep","목동점 ABCD123"),offer(ProcurementMall.HI_THEHYUNDAI,"unknown","ABCD123")),true,null),()->true);assertThat(calls).containsExactly("keep","unknown");assertThat(result.suppliers()).hasSize(2);}
    Offer smart(String id){return new Offer("NV"+id,"ABCD123","스마트스토어",id,"https://smartstore.naver.com/seller/products/"+id,94000L,0L,ProcurementMall.NAVER_SMART_STORE,null);}
    Store store(String id,ProcurementMall mall,String retailer,String name){return new Store(id,mall,"BRANCH",name,"branch:"+retailer+":"+name,List.of(name),0,retailer,List.of());}
    SupplierRefreshService.Work work(long run,Preferences prefs,Map<String,String> manual){return new SupplierRefreshService.Work(run,2,0,"ABCD123","ABCD6F123BK","LF_ACCESSORY","HAZZYS",prefs,manual);}
    @Test void failedInspectionStillFiltersBranchesAndSkipsManualNonPreferredBeforeNetwork(){
        var calls=new ArrayList<String>();
        SupplierProductGateway gateway=new SupplierProductGateway(){
            public String validateUrl(ProcurementMall m,String u){return u;}
            public List<SourceOption> options(ProcurementMall m,String id,String u){calls.add(id);throw new IllegalStateException("timeout");}
        };
        var manual=offer(ProcurementMall.HI_THEHYUNDAI,"manual","ABCD123");
        var result=new SupplierLookupService(gateway,new BusinessTime()).lookup(work(1,specific,Map.of(SupplierStorePolicy.listingKey(manual),"other")),
            new SearchResult(List.of(offer(ProcurementMall.HI_THEHYUNDAI,"wrong","천호점 ABCD123"),offer(ProcurementMall.HI_THEHYUNDAI,"right","목동점 ABCD123"),offer(ProcurementMall.HI_THEHYUNDAI,"unknown","ABCD123"),manual),true,null),()->true);
        assertThat(calls).containsExactly("right","unknown");
        assertThat(result.suppliers()).extracting(r->r.offer().mallProductId()).containsExactly("right","unknown");
        assertThat(result.suppliers()).allMatch(r->r.state().equals("FAILED"));
    }
    @Test void departmentPreferencesKeepUnknownWindowStoreForReviewButExcludeVerifiedGeneralSeller(){
        var st=store("1",ProcurementMall.NAVER_SMART_STORE,"현대백화점","천호점");
        var prefs=new Preferences(1,List.of(st),List.of(new Rule("2",st.mall(),st.id(),0)));
        SupplierProductGateway gateway=new SupplierProductGateway(){
            public String validateUrl(ProcurementMall m,String u){return u;}
            public List<SourceOption> options(ProcurementMall m,String id,String u){throw new AssertionError("스마트스토어 재고를 조회하면 안 됩니다.");}
            public SourceDetails inspect(ProcurementMall m,String id,String u){
                if(id.equals("13"))throw new IllegalStateException("timeout");
                StoreEvidence evidence=switch(id){
                    case "10"->new StoreEvidence("BRANCH","현대백화점","천호점","CHANNEL","10");
                    case "11"->new StoreEvidence("BRANCH","롯데백화점","천호점","CHANNEL","11");
                    case "14"->new StoreEvidence("SELLER",null,"일반 판매자","CHANNEL","14");
                    default->null;
                };
                return new SourceDetails("ABCD123","","",List.of(),null,evidence,true,new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT"));
            }
        };
        var result=new SupplierLookupService(gateway,new BusinessTime()).lookup(work(1,prefs,Map.of()),new SearchResult(java.util.stream.IntStream.rangeClosed(10,14).mapToObj(n->new Offer("NV"+n,"ABCD123","백화점",Integer.toString(n),"https://shopping.naver.com/window-products/department/"+n,94000L,0L,ProcurementMall.NAVER_SMART_STORE,null)).toList(),true,null),()->true);
        assertThat(result.suppliers()).extracting(r->r.offer().mallProductId()).containsExactly("10","12","13");
        assertThat(result.suppliers()).extracting(SupplierResult::state).containsExactly("OPTIONS_UNKNOWN","OPTIONS_UNKNOWN","FAILED");
        assertThat(result.suppliers()).allMatch(r->r.options().isEmpty());
        assertThat(result.suppliers().get(1).branch().state()).isEqualTo("UNKNOWN");
    }
    @Test void excludedMallsAndRepeatedOffersDoNotInspectOrHeartbeatAndCacheIsLimitedToRun(){
        var calls=new ArrayList<String>();var beats=new java.util.concurrent.atomic.AtomicInteger();
        SupplierProductGateway gateway=new SupplierProductGateway(){
            public String validateUrl(ProcurementMall m,String u){return u;}
            public List<SourceOption> options(ProcurementMall m,String id,String u){calls.add(id);return List.of(new SourceOption("FREE","FREE",2L,"AVAILABLE"));}
        };
        var prefs=new Preferences(1,List.of(),List.of(new Rule("1",ProcurementMall.LFMALL,null,0)));
        var lf=offer(ProcurementMall.LFMALL,"lf","ABCD123");
        var hmall=new Offer("hm","ABCD123","현대Hmall","1","https://www.hmall.com/p/pda/itemPtc.do?slitmCd=1",1L,0L,ProcurementMall.HMALL,null);
        var search=new SearchResult(List.of(hmall,lf,lf),true,null);
        var lookup=new SupplierLookupService(gateway,new BusinessTime());
        for(long run:List.of(1L,1L,2L))assertThat(lookup.lookup(work(run,prefs,Map.of()),search,()->{beats.incrementAndGet();return true;}).suppliers()).hasSize(1);
        assertThat(calls).containsExactly("lf","lf");assertThat(beats.get()).isEqualTo(3);
    }

    @Test void unresolvedProductAddressesUseBranchFilterWithoutSupplierRequests(){
        SupplierProductGateway gateway=new SupplierProductGateway(){
            public String validateUrl(ProcurementMall m,String u){throw new AssertionError();}
            public List<SourceOption> options(ProcurementMall m,String id,String u){throw new AssertionError();}
        };
        var offers=List.of("목동점","천호점","").stream().map(name->new Offer("NV"+name,"ABCD123 "+name,"더현대Hi","","https://hi.thehyundai.com/",100L,0L,ProcurementMall.HI_THEHYUNDAI,null)).toList();
        var result=new SupplierLookupService(gateway,new BusinessTime()).lookup(work(1,specific,Map.of()),new SearchResult(offers,true,null),()->true);
        assertThat(result.suppliers()).hasSize(2).allMatch(r->r.state().equals("REVIEW"));
        assertThat(result.suppliers()).noneMatch(r->"천호점".equals(r.branch().name()));
    }
    @Test void largeRunEvictsOldDetailsButKeepsRecentResponsesReusable(){
        var calls=new ArrayList<String>();
        SupplierProductGateway gateway=new SupplierProductGateway(){
            public String validateUrl(ProcurementMall m,String u){return u;}
            public List<SourceOption> options(ProcurementMall m,String id,String u){calls.add(id);return List.of();}
        };
        var prefs=new Preferences(1,List.of(),List.of(new Rule("1",ProcurementMall.LFMALL,null,0)));
        var offers=java.util.stream.IntStream.range(0,401).mapToObj(i->offer(ProcurementMall.LFMALL,"item"+i,"ABCD123")).toList();
        var lookup=new SupplierLookupService(gateway,new BusinessTime());
        lookup.lookup(work(1,prefs,Map.of()),new SearchResult(offers,true,null),()->true);
        lookup.lookup(work(1,prefs,Map.of()),new SearchResult(List.of(offers.getLast(),offers.getFirst()),true,null),()->true);
        assertThat(calls).hasSize(402);assertThat(calls.getLast()).isEqualTo("item0");
    }

    @Test void legacySnapshotDefaultsToRequiredAndBranchlessModeIgnoresManualBranchFilter(){
        var json=new tools.jackson.databind.ObjectMapper();
        var legacy=json.readValue("{\"revision\":1,\"stores\":[],\"rules\":[]}",Preferences.class);
        assertThat(legacy.branchRequired(ProcurementMall.LFMALL)).isTrue();
        var off=new Preferences(specific.revision(),specific.stores(),specific.rules(),Map.of(ProcurementMall.HI_THEHYUNDAI,false));
        var offer=offer(ProcurementMall.HI_THEHYUNDAI,"a","천호점 ABCD123");
        assertThat(SupplierStorePolicy.include(off,Map.of(SupplierStorePolicy.listingKey(offer),"other"),offer,branch("천호점"))).isTrue();
        assertThat(off.groups().getFirst().scope()).isEqualTo("ALL");
        assertThat(json.readValue(json.writeValueAsString(off),Preferences.class).branchRequired(ProcurementMall.HI_THEHYUNDAI)).isFalse();
    }

}
