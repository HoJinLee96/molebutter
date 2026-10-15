package cc.ataglace.molebutter.marketplace.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDocuments;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MarketplaceSnapshotCompatibilityTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void elevenstAndLotteonAddressChoicesSurviveServerDraftNormalization(){
        var common=new Common("CODE","상품명","","","","","","","","TAX","EVERYONE");
        for(var market:List.of(Market.ELEVENST,Market.LOTTEON)){
            String code=market==Market.LOTTEON?"PLO00008":"14";
            var address=new AddressSelection(code,"본사","12345","서울","1층","010");
            var config=new MarketConfig("",null,null,null,null,null,new AddressBook(address,address));
            var d=new Document(null,null,common,List.of(),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(market),Map.of(market,config));
            var restored=MarketplaceDocuments.normalize(json.readValue(json.writeValueAsString(d),Document.class));
            assertThat(restored.markets().get(market).addressBook().outbound().code()).isEqualTo(code);
        }
    }
    @Test void oldOptionPriceIsNotReinterpretedAndNormalPriceIsAdditive(){
        var old=json.readValue("{\"id\":\"10000000-0000-4000-8000-000000000001\",\"name\":\"FREE\",\"sku\":\"SKU\",\"price\":\"99000\",\"quantity\":\"0\",\"attributes\":[]}",Option.class);
        assertThat(old.price()).isEqualTo("99000");assertThat(old.originalPrice()).isNull();
        var common=new Common("CODE","상품명","","","","","","","","TAX","EVERYONE");var document=new Document(null,null,common,List.of(old),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(),Map.of());
        var normalized=MarketplaceDocuments.normalize(document);assertThat(normalized.options().getFirst().originalPrice()).isEmpty();assertThat(normalized.options().getFirst().price()).isEqualTo("99000");
        var option=new Option(old.id(),old.name(),old.sku(),"99000","0",List.of(),"128000");assertThat(json.readValue(json.writeValueAsString(option),Option.class)).isEqualTo(option);
    }
    @Test void nativeNaverAssetsAndEditorStateSurviveCommonSnapshot(){
        String image="10000000-0000-4000-8000-000000000001",description="10000000-0000-4000-8000-000000000002";
        var state=new EditorState(1,List.of("originProduct.name"),"COMMON_MINIMUM","COMMON","COMMON",null,"COMMON",null,List.of());
        var input=new cc.ataglace.molebutter.marketplace.api.NaverEditor.Input(Map.of("originProduct.name","네이버"),"NONE",List.of(),List.of(),List.of(new cc.ataglace.molebutter.marketplace.api.NaverEditor.Image("20000000-0000-4000-8000-000000000001",image,"/api/marketplaces/assets/"+image,true,0)),"<img src=\"/api/marketplaces/assets/"+description+"\">");
        var naver=new Naver("","SALE","NEW","00","","",List.of(),List.of(),null,null,null,input,null,state);
        var common=new Common("CODE","공통","","","","","","","","TAX","EVERYONE");
        var reference=new Document("1",2L,common,List.of(),StockMode.PRODUCT,"0",List.of(),new Media(List.of(),List.of()),null,List.of(Market.NAVER),Map.of(Market.NAVER,new MarketConfig("",null,null,naver,null)));
        var restored=MarketplaceDocuments.normalize(json.readValue(json.writeValueAsString(reference),Document.class));
        assertThat(restored.markets().get(Market.NAVER).naver().editorState()).isEqualTo(state);assertThat(MarketplaceDocuments.assetIds(restored)).containsExactlyInAnyOrder(image,description);
    }
    @Test void historicalKeysAndMissingVersionKeepLegacyMeaningAndNewFieldsAreAdditive(){
        var historical=json.readValue("""
            {"accountKey":"account","mapping":{"accountKey":"account","sellerProductId":"123","options":[{"optionId":"internal-id","sellerProductItemId":"0","vendorItemId":null}]},"steps":[],"changes":[],"preparedAt":"2026-10-08T00:00:00Z","expectedSkus":["SKU"]}
            """,MarketplaceWriteGateway.Prepared.class);
        assertThat(historical.schemaVersion()).isEqualTo(1);assertThat(historical.listingVersion()).isEqualTo(1);assertThat(historical.market()).isEqualTo("COUPANG");assertThat(historical.mapping().channelProductId()).isNull();assertThat(historical.mapping().options().getFirst().sellerProductItemId()).isEqualTo("0");assertThat(historical.editIntent()).isNull();
        var wire=json.valueToTree(historical);assertThat(wire.path("mapping").path("sellerProductId").asString()).isEqualTo("123");assertThat(wire.path("schemaVersion").asInt()).isEqualTo(1);assertThat(wire.has("externalProductId")).isFalse();
    }
    @Test void futureVersionIsRetainedInsteadOfSilentlyCoercedToCurrent(){
        var future=json.readValue("""
            {"accountKey":"account","mapping":null,"steps":[],"changes":[],"preparedAt":"2026-10-08T00:00:00Z","expectedSkus":[],"market":"NAVER","schemaVersion":9}
            """,MarketplaceWriteGateway.Prepared.class);
        assertThat(future.schemaVersion()).isEqualTo(9);assertThat(future.market()).isEqualTo("NAVER");
    }
    @Test void commonNaverBrandSelectionIsAdditiveAndSurvivesDraftNormalization(){
        var historical=json.readValue("""
            {"channelId":"channel","status":"SALE","saleType":"NEW","originCode":"00",
             "deliveryTemplateId":"shipping","afterServiceTelephone":"","attributes":[],"notices":[]}
            """,Naver.class);
        assertThat(historical.brandId()).isNull();assertThat(historical.editorInput()).isNull();
        var naver=new Naver("channel","SALE","NEW","00","shipping","",List.of(),List.of(),null,null,null,null,"9007199254740993");
        var common=new Common("CODE","상품명","","공통브랜드","","","","","","TAX","EVERYONE");
        var input=new Document(null,null,common,List.of(),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(Market.COUPANG),Map.of(Market.NAVER,new MarketConfig("",null,null,naver,null)));
        var normalized=MarketplaceDocuments.normalize(json.readValue(json.writeValueAsString(input),Document.class));
        var preserved=normalized.markets().get(Market.NAVER).naver();assertThat(preserved.brandId()).isEqualTo("9007199254740993");assertThat(preserved.editorInput()).isNull();assertThat(preserved.attributes()).isEmpty();assertThat(normalized.common().brand()).isEqualTo("공통브랜드");
        assertThat(new Naver("","","","","","",List.of(),List.of()).brandId()).isNull();
        assertThat(new Naver("","","","","","",List.of(),List.of(),null,null,null).brandId()).isNull();
        assertThat(new Naver("","","","","","",List.of(),List.of(),null,null,null,null).brandId()).isNull();
    }
    @Test void lotteonBrandSelectionIsAdditiveAndSurvivesDraftRoundTrip(){
        var historical=json.readValue("{\"categoryCode\":\"\",\"overrides\":null,\"coupang\":null,\"naver\":null,\"esm\":null}",MarketConfig.class);
        assertThat(historical.lotteon()).isNull();assertThat(new MarketConfig("",null,null,null,null).lotteon()).isNull();
        var common=new Common("CODE","상품명","","공통브랜드","","","","","","TAX","EVERYONE");
        var lotteon=new MarketConfig("",new Overrides(null,null,"롯데브랜드",List.of(),null,null),null,null,null,new Lotteon("P134"));
        var input=new Document(null,null,common,List.of(),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(Market.COUPANG),Map.of(Market.LOTTEON,lotteon));
        var saved=MarketplaceDocuments.normalize(json.readValue(json.writeValueAsString(input),Document.class));
        assertThat(saved.markets().get(Market.LOTTEON).lotteon().brandId()).isEqualTo("P134");
        assertThat(saved.markets().get(Market.LOTTEON).overrides().brand()).isEqualTo("롯데브랜드");assertThat(saved.selectedMarkets()).containsExactly(Market.COUPANG);
        var invalid=new Document(null,null,common,List.of(),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(),
            Map.of(Market.LOTTEON,new MarketConfig("",null,null,null,null,new Lotteon("P1234567890123"))));
        assertThatThrownBy(()->MarketplaceDocuments.normalize(invalid)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    @Test void lotteonBrandNameSupportsDocumented255CharactersWithoutChangingOtherMarketLimits(){
        var common=new Common("CODE","상품명","","공통브랜드","","","","","","TAX","EVERYONE");
        for(int length:List.of(201,255)){
            String name="가".repeat(length);
            var config=new MarketConfig("",new Overrides(null,null,name,List.of(),null,null),null,null,null,new Lotteon("P134"));
            var input=new Document(null,null,common,List.of(),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(),Map.of(Market.LOTTEON,config));
            var saved=MarketplaceDocuments.normalize(json.readValue(json.writeValueAsString(input),Document.class));
            assertThat(saved.markets().get(Market.LOTTEON).overrides().brand()).isEqualTo(name);
        }
        for(var market:Market.values()){
            String tooLong="가".repeat(market==Market.LOTTEON?256:201);
            var config=new MarketConfig("",new Overrides(null,null,tooLong,List.of(),null,null),null,null,null);
            var invalid=new Document(null,null,common,List.of(),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(),Map.of(market,config));
            assertThatThrownBy(()->MarketplaceDocuments.normalize(invalid)).as(market.name()).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        }
        var longCommon=new Common("CODE","상품명","","가".repeat(201),"","","","","","TAX","EVERYONE");
        var invalidCommon=new Document(null,null,longCommon,List.of(),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(),Map.of());
        assertThatThrownBy(()->MarketplaceDocuments.normalize(invalidCommon)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }

    @Test void optionCropShadowsOnlyItsSourceAndPreservesDescriptionPosition(){
        String option=UUID.randomUUID().toString(),other=UUID.randomUUID().toString(),image=UUID.randomUUID().toString(),content=UUID.randomUUID().toString(),asset=UUID.randomUUID().toString();
        var sourceImage=new Image(image,null,"https://assets.test/source.jpg",true,0,null);
        var cropImage=new Image(UUID.randomUUID().toString(),asset,"/api/marketplaces/assets/"+asset,true,0,option,null,image);
        var source=new Content(content,"IMAGE","https://assets.test/source.jpg",null);
        var tail=new Content(UUID.randomUUID().toString(),"HTML","<p>뒤 설명</p>",null);
        var crop=new Content(UUID.randomUUID().toString(),"IMAGE","/api/marketplaces/assets/"+asset,option,asset,content);
        var common=new Common("CODE","상품명","","","","","","","","TAX","EVERYONE");
        var d=new Document(null,null,common,List.of(new Option(option,"A","","1","1",List.of()),new Option(other,"B","","1","1",List.of())),StockMode.OPTION,"",List.of(),new Media(List.of(sourceImage,cropImage),List.of(source,tail,crop)),null,List.of(),Map.of());
        var restored=MarketplaceDocuments.normalize(json.readValue(json.writeValueAsString(d),Document.class));
        assertThat(MarketplaceDocuments.imagesFor(restored,option)).containsExactly(cropImage);assertThat(MarketplaceDocuments.imagesFor(restored,other)).containsExactly(sourceImage);
        assertThat(MarketplaceDocuments.contentsFor(restored,option)).containsExactly(crop,tail);assertThat(MarketplaceDocuments.contentsFor(restored,other)).containsExactly(source,tail);assertThat(MarketplaceDocuments.assetIds(restored)).containsExactly(asset);
    }
}
