package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.internal.SearchCompletion;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import cc.ataglace.molebutter.procurement.internal.ProductCandidateSearch;

class SearchCompletionTest {
    final LocalDateTime at=LocalDateTime.parse("2026-09-28T03:52:14");
    SupplierResult listing(String state,Long stock,String optionState,Long fee){
        return new SupplierResult(new Offer("nv","bag","LF몰","1","https://www.lfmall.co.kr/product/1",100L,fee,ProcurementMall.LFMALL,null),
            new CodeMatch("SEARCH_RESULT",null,null,null,null,null),state,state.equals("SKIPPED_SAME_STORE")||state.equals("FAILED")?List.of():List.of(new SourceOption("one","FREE",stock,optionState)),null);
    }
    RefreshResult summarize(List<SupplierResult> rows,String reason,boolean limited){return SearchCompletion.summarize(rows,reason,at,null,Map.of(),limited,List.of());}
    @Test void normalPageLimitAndEndOfResultsAreSuccessfulDespiteIntentionalSkips(){
        var rows=new ArrayList<SupplierResult>();for(int i=0;i<33;i++)rows.add(listing("CONFIRMED",98L,"AVAILABLE",0L));
        for(int i=0;i<22;i++)rows.add(listing("SKIPPED_SAME_STORE",null,null,0L));
        for(String reason:List.of("COMPLETED","PAGE_LIMIT","END_OF_RESULTS")){
            var r=summarize(rows,reason,false);assertThat(r.status()).isEqualTo("SUCCESS");assertThat(r.suppliers()).hasSize(55);
            assertThat(r.suppliers().stream().filter(SupplierResult::skipped)).allMatch(s->s.options().isEmpty());
            assertThat(r.completionReason()).isEqualTo("COMPLETED");assertThat(r.checkedAt()).isEqualTo(at);
        }
        assertThat(summarize(rows,"PAGE_LIMIT",false).message()).isNull();
        assertThat(summarize(rows,null,false).status()).isEqualTo("PARTIAL");
    }
    @Test void actualDcwaSnapshotBecomesSuccessfulWithoutChangingStock()throws Exception{
        var data=FixtureText.read("product/dcwa279bk-stock-summary.json");
        var old=JsonMapper.builder().build().readValue(data,RefreshResult.class);
        assertThat(old.suppliers().stream().filter(SupplierResult::skipped).count()).isEqualTo(22);
        var updated=summarize(old.suppliers(),"PAGE_LIMIT",false);
        assertThat(updated.status()).isEqualTo("SUCCESS");assertThat(updated.suppliers()).isEqualTo(old.suppliers());
    }
    @Test void stockAndPriceProblemsStillRequireAttention(){
        var healthy=listing("CONFIRMED",10L,"AVAILABLE",0L);
        for(var bad:List.of(listing("CONFIRMED",null,"STOCK_UNKNOWN",0L),listing("OPTIONS_PARTIAL",10L,"AVAILABLE",0L),listing("FAILED",null,null,0L),listing("CONFIRMED",10L,"AVAILABLE",null))){
            assertThat(summarize(List.of(healthy,bad),"PAGE_LIMIT",false).status()).isEqualTo("PARTIAL");
        }
    }
    @Test void emptySoldOutAndRecommendationLimitKeepDistinctStatuses(){
        assertThat(summarize(List.of(),"END_OF_RESULTS",false).status()).isEqualTo("SOLD_OUT");
        assertThat(summarize(List.of(),"PAGE_LIMIT",false).status()).isEqualTo("SOLD_OUT");
        assertThat(summarize(List.of(listing("CONFIRMED",0L,"SOLD_OUT",0L)),"PAGE_LIMIT",false).status()).isEqualTo("SOLD_OUT");
        var limited=summarize(List.of(listing("CONFIRMED",10L,"AVAILABLE",0L)),"PAGE_LIMIT",true);
        assertThat(limited.status()).isEqualTo("SUCCESS");assertThat(limited.message()).contains("추천 일부 조회");assertThat(limited.recommendationLimited()).isTrue();
    }
    @Test void legacyMessagesMustBeExactAndMissingJsonNeverMeansSuccessfulSearch(){
        var json=JsonMapper.builder().build();
        for(String field:List.of("",",\"completionReason\":null")){
            var search=json.readValue("{\"offers\":[],\"complete\":false,\"message\":null"+field+"}",SearchResult.class);
            assertThat(SearchCompletion.reason(search)).isNull();
        }
        assertThat(SearchCompletion.reason(new SearchResult(List.of(),false,"최대 3페이지 범위의 검색 결과입니다. 이후 페이지는 확인하지 않았습니다."))).isEqualTo("COMPLETED");
        assertThat(SearchCompletion.reason(new SearchResult(List.of(),false,"3페이지 조회 실패"))).isNull();
        var search=new SearchResult(List.of(),false,null,"PAGE_LIMIT");
        var encoded=json.writeValueAsString(search);
        assertThat(encoded).doesNotContain("PAGE_LIMIT","최대 3페이지");
        var copy=json.readValue(encoded,SearchResult.class);
        assertThat(copy.complete()).isTrue();assertThat(SearchCompletion.reason(copy)).isEqualTo("COMPLETED");
        assertThat(ProductCandidateSearch.classify(copy).completionReason()).isEqualTo("COMPLETED");
    }
}
