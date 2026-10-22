package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import java.math.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.coupang.api.*;
import static org.assertj.core.api.Assertions.*;
class CoupangRecommendationsTest {
    final CoupangPricing pricing=new DefaultCoupangPricing(null,null);
    final CoupangPricing.Settings settings=new CoupangPricing.Settings(0,4000,new BigDecimal("10.5"),new BigDecimal("7"),null,null);
    CoupangRecommendationSources.Source source(Long cost,boolean previous,List<CoupangRecommendationSources.Stock> stocks,int count){return new CoupangRecommendationSources.Source("41",cost,previous,"2026-10-16T10:00:00",stocks,count,"AVAILABLE",previous,"2026-10-16T10:00:00",0L,0);}
    CoupangRegisteredProducts.Recommendation evaluate(Long price,Long stock,CoupangRecommendationSources.Source source){return CoupangRecommendations.evaluate(pricing,settings,price,stock,source);}
    @Test void adjustedPriceBoundaryAndPreviousPriceAreUsedWithoutRecommendingLowPositiveMargins(){
        var evidence=source(115170L,true,List.of(),0);
        assertThat(evaluate(147000L,3L,evidence)).isNull();
        var high=evaluate(147010L,3L,evidence);assertThat(high.reasons()).containsExactly("PRICE_HIGH");assertThat(high.suggestedPrice()).isEqualTo(147000);assertThat(high.previousPrice()).isTrue();
        assertThat(evaluate(143000L,3L,evidence)).isNull();
        assertThat(evaluate(100000L,3L,evidence).reasons()).containsExactly("NEGATIVE_MARGIN");
        assertThat(evaluate(260000L,1L,source(206080L,false,List.of(),0)).suggestedPrice()).isEqualTo(259000);
    }
    @Test void exactBreakEvenIsNotNegativeAndSmallRealLossIsNegative(){
        var s=new CoupangPricing.Settings(0,4000,BigDecimal.ZERO,new BigDecimal("7"),null,null);
        var zero=CoupangRecommendations.evaluate(pricing,s,10000L,1L,source(6000L,false,List.of(),0));
        assertThat(pricing.calculate(s,6000,10000L).profit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.reasons()).doesNotContain("NEGATIVE_MARGIN");
        var loss=CoupangRecommendations.evaluate(pricing,s,10000L,1L,source(6001L,false,List.of(),0));
        assertThat(loss.reasons()).contains("NEGATIVE_MARGIN");assertThat(loss.profit()).isNegative();
    }
    @Test void independentStockReasonsPreserveFullOptionCountAndDeduplicateRows(){
        var stocks=List.of(new CoupangRecommendationSources.Stock("s1","블랙",1,"AVAILABLE"));
        var multiple=source(115170L,false,stocks,3);
        var rec=evaluate(150000L,0L,multiple);assertThat(rec.reasons()).containsExactly("PRICE_HIGH","RESTOCK");assertThat(rec.stockOptionCount()).isEqualTo(3);assertThat(rec.stocks()).hasSize(1);
        assertThat(evaluate(null,0L,source(null,false,stocks,3)).reasons()).containsExactly("RESTOCK");
        assertThat(evaluate(null,1L,multiple)).isNull();assertThat(evaluate(null,null,multiple)).isNull();
        var prior=new CoupangRecommendationSources.Source("41",115170L,true,"2026-10-15T10:00:00",List.of(new CoupangRecommendationSources.Stock("s1","FREE",144,"AVAILABLE")),1,"AVAILABLE",true,"2026-10-15T10:00:00",0L,0);
        var restock=evaluate(143000L,0L,prior);assertThat(restock.reasons()).containsExactly("RESTOCK");assertThat(restock.stockProposal().quantity()).isEqualTo(144);assertThat(restock.previousAvailability()).isTrue();assertThat(restock.availabilityCheckedAt()).isEqualTo("2026-10-15T10:00:00");
        assertThat(evaluate(150000L,0L,null)).isNull();assertThat(evaluate(null,0L,source(null,false,List.of(),3))).isNull();
        assertThat(evaluate(null,0L,source(0L,false,stocks,1)).reasons()).containsExactly("RESTOCK");
    }
    @Test void settingsChangesRecomputeAndThreeReasonsStillProduceOneRecommendation(){
        var evidence=source(115170L,false,List.of(),0);
        assertThat(evaluate(150000L,1L,evidence).reasons()).containsExactly("PRICE_HIGH");
        var changed=new CoupangPricing.Settings(1,4000,new BigDecimal("10.5"),new BigDecimal("15"),null,null);
        assertThat(CoupangRecommendations.evaluate(pricing,changed,150000L,1L,evidence)).isNull();
        assertThat(pricing.calculate(settings,13690,20000L).profit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(evaluate(20000L,1L,source(13690L,false,List.of(),0))).isNull();
        assertThat(evaluate(20000L,1L,source(13691L,false,List.of(),0)).reasons()).containsExactly("NEGATIVE_MARGIN");
        var zeroMargin=new CoupangPricing.Settings(0,0,BigDecimal.ZERO,BigDecimal.ZERO,null,null);
        var all=CoupangRecommendations.evaluate(pricing,zeroMargin,149500L,0L,source(150000L,false,List.of(new CoupangRecommendationSources.Stock("a","FREE",1,"AVAILABLE")),1));
        assertThat(all.reasons()).containsExactly("PRICE_HIGH","NEGATIVE_MARGIN","RESTOCK");
    }
    @Test void suggestedLossIsSurfacedWithoutRaisingCalculatedPrice(){
        var s=new CoupangPricing.Settings(0,0,BigDecimal.ZERO,BigDecimal.ZERO,null,null);
        var rec=CoupangRecommendations.evaluate(pricing,s,151000L,1L,source(150000L,false,List.of(),0));
        assertThat(rec.suggestedPrice()).isEqualTo(149000);assertThat(rec.suggestedLoss()).isTrue();
    }

    CoupangRecommendationSources.Source unavailable(long owned,int options){return new CoupangRecommendationSources.Source("41",115170L,true,"2026-10-16T10:00:00",List.of(),1,"UNAVAILABLE",true,"2026-10-15T10:00:00",owned,options);}
    @Test void zeroAvailableSupplierSuppressesBothPriceReasonsAndOnlyRecommendsNeededStockChanges(){
        assertThat(evaluate(100000L,0L,unavailable(0,0))).isNull();
        var out=evaluate(200000L,10L,unavailable(0,0));assertThat(out.reasons()).containsExactly("MARK_SOLD_OUT");assertThat(out.stockProposal().quantity()).isZero();assertThat(out.previousAvailability()).isTrue();
        assertThat(evaluate(100000L,5L,unavailable(5,1))).isNull();
        for(long current:List.of(0L,2L,8L)){
            var owned=evaluate(100000L,current,unavailable(5,1));assertThat(owned.reasons()).containsExactly("OWNED_STOCK_MISMATCH");assertThat(owned.stockProposal().quantity()).isEqualTo(5);
        }
        var many=evaluate(100000L,5L,unavailable(5,2));assertThat(many.stockProposal().selectionRequired()).isTrue();assertThat(many.stockProposal().quantity()).isNull();
        var coupons=CoupangRecommendations.evaluate(pricing,settings,100000L,5L,unavailable(5,1),2);assertThat(coupons.stockProposal().selectionRequired()).isTrue();
        var unknown=new CoupangRecommendationSources.Source("41",115170L,false,null,List.of(),2,"UNKNOWN",false,null,0L,0);assertThat(evaluate(100000L,50L,unknown)).isNull();
    }
    @Test void missingListingProposesZeroDespiteOldOneAndPreservesOwnedAllocationRules() {
        var old=List.of(new CoupangRecommendationSources.Stock("a","FREE",1L,"AVAILABLE"));
        var missing=new CoupangRecommendationSources.Source("41",46820L,true,"2026-10-19T10:00:00",old,1,"SELECTED_MISSING",true,"2026-10-20T10:00:00",0L,0,"SELECTED_LISTING_MISSING");
        var rec=evaluate(62000L,49L,missing);assertThat(rec.reasons()).containsExactly("MARK_SOLD_OUT");assertThat(rec.stockProposal().quantity()).isZero();assertThat(rec.availabilityReason()).isEqualTo("SELECTED_LISTING_MISSING");
        assertThat(evaluate(1000L,0L,missing)).isNull();
        var owned=new CoupangRecommendationSources.Source("41",46820L,true,null,old,1,"SELECTED_MISSING",false,"2026-10-20T10:00:00",3L,1,"SELECTED_LISTING_MISSING");
        assertThat(evaluate(62000L,49L,owned).stockProposal().quantity()).isEqualTo(3);
        assertThat(evaluate(62000L,3L,owned)).isNull();
        var multi=CoupangRecommendations.evaluate(pricing,settings,62000L,49L,owned,2);assertThat(multi.reasons()).containsExactly("OWNED_STOCK_MISMATCH");assertThat(multi.stockProposal().selectionRequired()).isTrue();assertThat(multi.stockProposal().quantity()).isNull();
    }
}
