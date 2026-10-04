package cc.ataglace.molebutter.product;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.*;
import cc.ataglace.molebutter.dto.product.ChangeDtos.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.ProcurementMall;
import cc.ataglace.molebutter.service.product.ProductChangePolicy;

class ProductChangePolicyTest {
    final LocalDateTime at=LocalDateTime.parse("2026-09-28T03:52:14");
    OptionValue option(String id,Long stock,String state){return new OptionValue(id,"블랙","OPTION",stock,state,at);}
    Observation value(Long price,boolean partial,OptionValue... options){return new Observation("1","HI:1:nv",ProcurementMall.HI_THEHYUNDAI,"store","store:channel","현대 천호","https://example.test",price,0L,at,List.of(options),partial,true,true,true,at);}
    @Test void comparesPriceFeeAndExactOptionWithoutSummingListings(){
        var b=value(156450L,false,option("black",100L,"AVAILABLE"));var n=value(149900L,false,option("black",37L,"AVAILABLE"));
        assertThat(ProductChangePolicy.compare(b,n)).extracting(Delta::difference).containsExactly(-6550L,-63L);
        assertThat(ProductChangePolicy.compare(b,b)).isEmpty();
    }
    @Test void unqueriedAndFailedAreNotZeroOrRemovedOptions(){
        var b=value(156450L,false,option("black",100L,"AVAILABLE"));
        assertThat(ProductChangePolicy.compare(b,value(null,true))).isEmpty();
        assertThat(ProductChangePolicy.compare(b,value(156450L,true,option("black",null,"STOCK_UNKNOWN")))).isEmpty();
    }
    @Test void partialOptionsCompareOnlyObservedOptions(){
        var b=value(1L,false,option("a",100L,"AVAILABLE"),option("b",200L,"AVAILABLE"));
        assertThat(ProductChangePolicy.compare(b,value(1L,true,option("a",50L,"AVAILABLE")))).extracting(Delta::kind).containsExactly("STOCK");
    }
    @Test void optionNamesNeverJoinDifferentIdsAndScopeChangesNeverSubtract(){
        var b=value(1L,false,option("a",100L,"AVAILABLE"));
        assertThat(ProductChangePolicy.compare(b,value(1L,false,option("b",37L,"AVAILABLE")))).extracting(Delta::kind).containsExactly("OPTION_NEW","OPTION_MISSING");
        var total=new OptionValue("a","블랙","PRODUCT",37L,"AVAILABLE",at);
        assertThat(ProductChangePolicy.compare(b,value(1L,false,total))).allMatch(d->d.difference()==null);
    }
    @Test void onlyConfirmedPositiveStockFromSoldOutIsRestock(){
        var b=value(1L,false,option("a",0L,"SOLD_OUT"));
        assertThat(ProductChangePolicy.compare(b,value(1L,false,option("a",3L,"AVAILABLE")))).extracting(Delta::kind).contains("RESTOCK");
        assertThat(ProductChangePolicy.compare(b,value(1L,false,option("a",null,"AVAILABLE")))).extracting(Delta::kind).doesNotContain("RESTOCK");
        assertThat(ProductChangePolicy.compare(value(1L,true,option("a",null,"STOCK_UNKNOWN")),value(1L,false,option("a",3L,"AVAILABLE")))).isEmpty();
    }
    @Test void reviewKeepsValidNumbersWhenCurrentUnknown(){
        var b=value(156450L,false,option("a",100L,"AVAILABLE"));var unknown=value(null,true,option("a",null,"STOCK_UNKNOWN"));
        var merged=ProductChangePolicy.mergeValid(b,unknown);assertThat(merged.price()).isEqualTo(156450);assertThat(merged.options().getFirst().stock()).isEqualTo(100);
    }
    @Test void reviewOfCompleteNewOptionsReplacesStructureAndBaselineDoesNotAdvance(){
        var b=value(156450L,false,option("a",100L,"AVAILABLE"));var n=value(149900L,false,option("b",37L,"AVAILABLE"));
        assertThat(ProductChangePolicy.mergeValid(b,n).options()).extracting(OptionValue::id).containsExactly("b");
        var preserved=ProductChangePolicy.fillBaseline(b,n);assertThat(preserved.price()).isEqualTo(156450);assertThat(preserved.options().getFirst().stock()).isEqualTo(100);
    }
    @Test void firstKnownValuesEstablishBaselineAndAmbiguousIdsAreNotCompared(){
        var empty=value(null,true);var first=value(10L,false,option("a",37L,"AVAILABLE"));
        assertThat(ProductChangePolicy.compare(ProductChangePolicy.fillBaseline(empty,first),first)).isEmpty();
        assertThat(ProductChangePolicy.compare(first,value(10L,true,option("a",20L,"AVAILABLE"),option("a",40L,"AVAILABLE")))).isEmpty();
    }
    @Test void unknownQuantityAndDeliveryRetainTheirOwnObservationTimes(){
        var b=value(10L,false,option("a",100L,"AVAILABLE"));var later=at.plusDays(1);
        var n=new Observation(b.id(),b.key(),b.mall(),b.storeId(),b.identity(),b.name(),b.url(),20L,null,later,List.of(new OptionValue("a","블랙","OPTION",null,"UNAVAILABLE",later)),true,true,true,true,later);
        var m=ProductChangePolicy.mergeValid(b,n);
        assertThat(m.priceAt()).isEqualTo(later);assertThat(m.feeAt()).isEqualTo(at);assertThat(m.options().getFirst().checkedAt()).isEqualTo(at);assertThat(m.options().getFirst().stateAt()).isEqualTo(later);
    }
}
