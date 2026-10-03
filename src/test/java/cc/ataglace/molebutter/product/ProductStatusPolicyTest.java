package cc.ataglace.molebutter.product;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.service.product.*;
class ProductStatusPolicyTest {
    SupplierResult row(Mall mall,String id,String state,Long stock,String option){
        return new SupplierResult(new Offer(id,"bag",mall.name(),id,"https://example.test/"+id,100L,0L,mall,null),new CodeMatch("SEARCH_RESULT",null,null,null,null,null),state,
            "FAILED".equals(state)||"OPTIONS_UNKNOWN".equals(state)?List.of():List.of(new SourceOption("one","FREE",stock,option)),null);
    }
    @Test void unknownAndErrorsNeverBecomeSoldOutEvenWhenEveryRowIsUnknown(){
        for(var r:List.of(row(Mall.LFMALL,"a","OPTIONS_UNKNOWN",null,null),row(Mall.LFMALL,"a","CONFIRMED",null,"STOCK_UNKNOWN"),row(Mall.LFMALL,"a","FAILED",null,null),row(Mall.LFMALL,"a","OPTIONS_PARTIAL",0L,"SOLD_OUT")))
            assertThat(ProductStatusPolicy.assess(List.of(r),true).status()).isEqualTo("PARTIAL");
    }
    @Test void emptyAndExplicitZeroOrUnavailableAreSoldOutButRetainDifferentReasons(){
        assertThat(ProductStatusPolicy.assess(List.of(),true).reasons()).containsExactly("NO_TARGET_LISTINGS");
        var zero=row(Mall.LFMALL,"a","CONFIRMED",0L,"SOLD_OUT");var unavailable=row(Mall.LFMALL,"b","CONFIRMED",null,"UNAVAILABLE");
        assertThat(ProductStatusPolicy.assess(List.of(zero,unavailable),true).reasons()).containsExactly("ALL_UNAVAILABLE");
        assertThat(unavailable.options().getFirst().stock()).isNull();
        assertThat(ProductStatusPolicy.assess(List.of(),false).status()).isEqualTo("PARTIAL");
    }
    @Test void unselectedRecommendationCannotHideSoldOutOrCreatePartialButSelectedNonpreferredCounts(){
        var prefs=new Preferences(0,List.of(),List.of(new Rule("r",Mall.LFMALL,null,0)),Map.of(Mall.LFMALL,false,Mall.HAZZYS,false));
        var sold=row(Mall.LFMALL,"a","CONFIRMED",0L,"SOLD_OUT");var candidate=row(Mall.HAZZYS,"b","CONFIRMED",5L,"AVAILABLE");
        var result=SearchCompletion.summarize(List.of(sold,candidate),"COMPLETED",LocalDateTime.now(),prefs,Map.of(),false,List.of());
        assertThat(result.status()).isEqualTo("SOLD_OUT");assertThat(result.statusPolicyVersion()).isEqualTo(2);
        var selected=new SelectionBasis("s",SupplierStorePolicy.listingKey(candidate.offer()),null,null);
        assertThat(SearchCompletion.summarize(List.of(sold,candidate),"COMPLETED",LocalDateTime.now(),prefs,Map.of(),false,List.of(),selected).status()).isEqualTo("SUCCESS");
        var unknown=row(Mall.HAZZYS,"b","OPTIONS_UNKNOWN",null,null);
        assertThat(SearchCompletion.summarize(List.of(sold,unknown),"COMPLETED",LocalDateTime.now(),prefs,Map.of(),false,List.of()).status()).isEqualTo("SOLD_OUT");
        assertThat(SearchCompletion.summarize(List.of(sold,unknown),"COMPLETED",LocalDateTime.now(),prefs,Map.of(),false,List.of(),selected).status()).isEqualTo("PARTIAL");
    }
    @Test void positiveAndUnknownOrMissingFeeRemainPartial(){
        var good=row(Mall.LFMALL,"a","CONFIRMED",10L,"AVAILABLE");
        assertThat(ProductStatusPolicy.assess(List.of(good),true).status()).isEqualTo("SUCCESS");
        assertThat(ProductStatusPolicy.assess(List.of(good,row(Mall.LFMALL,"b","CONFIRMED",null,"STOCK_UNKNOWN")),true).status()).isEqualTo("PARTIAL");
    }
}
