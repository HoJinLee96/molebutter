package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.internal.NaverChannelPolicy;
import cc.ataglace.molebutter.procurement.internal.NaverSearchPayload;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

import cc.ataglace.molebutter.procurement.internal.ProductCandidateSearch;

class NaverSearchChannelTest {
    final NaverSearchPayload parser=new NaverSearchPayload(new ObjectMapper());
    SearchResult search(String fields){return parser.parse("{\"products\":[{\"nvMid\":\"NV\",\"mallName\":\"헤지스\",\"price\":1000,"+fields+"}],\"hasNext\":false}");}
    @Test void keepsStructuredChannelEvidenceThroughClassificationAndLegacyJson() throws Exception {
        var json=new ObjectMapper();var fixture=json.readTree(FixtureText.read("product/supplier-group/hiba-search.json"));
        var root=json.createObjectNode();root.set("products",fixture.path("rows"));root.put("hasNext",false);
        var result=ProductCandidateSearch.classify(parser.parse(json.writeValueAsString(root)));
        assertThat(result.offers()).hasSize(2);assertThat(result.offers()).allSatisfy(o->{assertThat(o.searchStore().channelId()).isEqualTo("1000008804");assertThat(o.searchStore().storeName()).isEqualTo("현대백화점 목동점");});
        var old=json.readValue("{\"naverProductId\":\"old\",\"title\":\"old\"}",Offer.class);assertThat(old.searchStore()).isNull();
    }
    @Test void trackingLinksUseRealProductUrlAndKeepWindowSubtype(){
        var result=search("\"purchaseUrl\":\"https://cr.shopping.naver.com/adcr?x=1\",\"mallProductUrl\":\"https://shopping.naver.com/window-products/department/42\"");
        var offer=ProductCandidateSearch.classify(result).offers().getFirst();
        assertThat(offer.mallProductId()).isEqualTo("42");assertThat(offer.naverChannel()).isEqualTo(new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT"));
    }
    @Test void allUrlHintsAreCheckedBeforeChoosingRequestTarget(){
        for(String extra:new String[]{"\"link\":\"https://smartstore.naver.com/lotte/products/42\"","\"link\":\"https://shopping.naver.com/window-products/brandfashion/42\"","\"mallProductId\":\"43\""}){
            var result=search("\"purchaseUrl\":\"https://shopping.naver.com/window-products/department/42\","+extra);
            if(extra.contains("mallProductId"))assertThat(result.offers()).isEmpty();
            else assertThat(result.offers()).singleElement().satisfies(o->assertThat(o.naverChannel().type()).isEqualTo(NaverChannelType.CONFLICT));
            assertThat(ProductCandidateSearch.classify(result).offers()).isEmpty();
        }
    }
    @Test void brandAndOutlinkUrlsRemainUnknownUntilAnApiResponse(){
        for(String url:new String[]{"https://brand.naver.com/hazzys/products/42","https://shopping.naver.com/outlink/itemdetail/42"}){
            var offer=ProductCandidateSearch.classify(search("\"link\":\""+url+"\"")).offers().getFirst();
            assertThat(NaverChannelPolicy.probeAllowed(offer)).isTrue();assertThat(NaverChannelPolicy.comparable(offer)).isFalse();
        }
    }
}
