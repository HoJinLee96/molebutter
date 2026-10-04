package cc.ataglace.molebutter.product;

import cc.ataglace.molebutter.service.common.BusinessTime;
import org.junit.jupiter.api.Test;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.service.product.*;

class NaverChannelPolicyTest {
    final String window="https://shopping.naver.com/window-products/department/42";
    Offer offer(String url,long price){return new Offer("NV", "가방", "롯데백화점", "42",url,price,0L,ProcurementMall.NAVER_SMART_STORE,null);}
    final Preferences prefs=new Preferences(1,List.of(),List.of(new Rule("1",ProcurementMall.NAVER_SMART_STORE,null,0)),Map.of(ProcurementMall.NAVER_SMART_STORE,false));
    SupplierRefreshService.Work work(Offer selected){return new SupplierRefreshService.Work(1,2,0,"가방","CODE","GENERAL",null,prefs,Map.of(),new SelectionBasis(selected==null?null:"selected",selected==null?null:SupplierStorePolicy.listingKey(selected),null,"change"));}
    @Test void legacyUrlsAndExplicitUnknownAreDifferent(){
        var json=new ObjectMapper();var old=offer(window,10000);
        assertThat(NaverChannelPolicy.comparable(old)).isTrue();
        assertThat(NaverChannelPolicy.comparable(old.withChannel(NaverChannelPolicy.unknown()))).isFalse();
        assertThat(NaverChannelPolicy.comparable(offer("https://brand.naver.com/daks/products/42",10000))).isFalse();
        assertThat(NaverChannelPolicy.comparable(offer("https://smartstore.naver.com/lotte/products/42",10000).withChannel(new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT")))).isFalse();
        String raw=json.writeValueAsString(old);
        assertThat(NaverChannelPolicy.comparable(json.readValue(raw,Offer.class))).isTrue();
        assertThat(json.readValue("{\"lookupPolicy\":\"SEARCH_QUERY\"}",SelectionBasis.class).usesWindowOnly()).isFalse();
    }
    @Test void responseMustConfirmChannelAndProductWithoutConflictingFields(){
        var json=new ObjectMapper();var parser=new MallOptionParser(json);
        String raw="{\"_id\":\"42\",\"channel\":{\"verticalType\":\"DEPARTMENT\"},\"contents\":{\"id\":42,\"channelServiceType\":\"WINDOW\",\"optionUsable\":false,\"stockQuantity\":50,\"statusType\":\"SALE\"}}";
        var detail=parser.details(ProcurementMall.NAVER_SMART_STORE,raw,"42");
        assertThat(NaverChannelPolicy.inspected(offer(window,10000),detail).type()).isEqualTo(NaverChannelType.WINDOW);
        assertThat(NaverChannelPolicy.inspected(offer(window.replace("department","brandfashion"),10000),detail).type()).isEqualTo(NaverChannelType.CONFLICT);
        for(String value:List.of(raw.replace("WINDOW","STOREFARM"),raw.replace("\"channelServiceType\":\"WINDOW\",",""),raw.replace("\"id\":42","\"id\":43"))){
            assertThat(NaverChannelPolicy.inspected(offer(window,10000),parser.details(ProcurementMall.NAVER_SMART_STORE,value,"42")).type()).isNotEqualTo(NaverChannelType.WINDOW);
        }
    }
    @Test void ordinarySelectedListingNeverTriggersDetailsOrRecommendations(){
        var calls=new ArrayList<String>();var selected=offer("https://smartstore.naver.com/lotte/products/42",10000);
        var gateway=new SupplierProductGateway(){public String validateUrl(ProcurementMall m,String url){return url;}public List<SourceOption> options(ProcurementMall m,String id,String url){calls.add(url);throw new AssertionError("Ordinary store must not be requested");}};
        var result=new SupplierLookupService(gateway,new BusinessTime()).lookup(work(selected),new SearchResult(List.of(selected),true,null),()->true);
        assertThat(calls).isEmpty();assertThat(result.suppliers()).isEmpty();assertThat(result.message()).isEqualTo("대상 판매글 없음");assertThat(result.searchPrice()).isNull();
    }
    @Test void ambiguousUrlNeedsPositiveApiEvidenceAndCannotUseManualBranchBypass(){
        var calls=new ArrayList<String>();var ambiguous=offer("https://brand.naver.com/daks/products/42",10000);
        for(var type:List.of(NaverChannelType.UNKNOWN,NaverChannelType.SMARTSTORE,NaverChannelType.WINDOW)){
            var gateway=new SupplierProductGateway(){public String validateUrl(ProcurementMall m,String url){return url;}public List<SourceOption> options(ProcurementMall m,String id,String url){return List.of();}
                public SourceDetails inspect(ProcurementMall m,String id,String url){calls.add(url);return new SourceDetails("","","",List.of(new SourceOption("42","상품 전체",50L,"AVAILABLE","PRODUCT")),"",null,true,new NaverChannel(type,"BRAND_FASHION"));}};
            var result=new SupplierLookupService(gateway,new BusinessTime()).lookup(work(null),new SearchResult(List.of(ambiguous),true,null),()->true);
            assertThat(result.suppliers()).hasSize(type==NaverChannelType.WINDOW?1:0);
            assertThat(result.message()).isEqualTo(type==NaverChannelType.WINDOW?null:"대상 판매글 없음");
        }
        var result=new SupplierResult(ambiguous,new CodeMatch("SEARCH_RESULT",null,null,null,null,null),"CONFIRMED",List.of(),null);
        assertThat(SupplierRecommendationPolicy.verified(prefs,Map.of(SupplierStorePolicy.listingKey(ambiguous),"fake"),result)).isFalse();
        assertThat(calls).hasSize(3);
    }
}
