package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ataglace.molebutter.marketplacecoupang.internal.ProviderCoupangDocumentsFixture;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

class DraftValidationTest {
    private static final String OPTION="00000000-0000-4000-8000-000000000001",IMAGE="00000000-0000-4000-8000-000000000002",ASSET="00000000-0000-4000-8000-000000000003",CONTENT="00000000-0000-4000-8000-000000000004";
    private static Document blank(){return new Document(null,null,new Common("","","","","","","","","","",""),List.of(new Option(OPTION,"","","","",List.of())),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(),Map.of());}
    private static Document complete(){
        var common=new Common("SKU","상품","제품","브랜드","제조사","한국","가죽","MODEL","A/S","TAX","EVERYONE");
        var option=new Option(OPTION,"블랙","SKU","0","0",List.of(new Attribute("색상","블랙"),new Attribute("Manufacturer Part Number","SKU")));
        var media=new Media(List.of(new Image(IMAGE,ASSET,null,true,0,null)),List.of(new Content(CONTENT,"HTML","<p>설명</p>",null)));
        var delivery=new Delivery("SEQUENCIAL","CJGLS","FREE","0","0","0","0","N","NOT_UNION_DELIVERY","123","456","담당자","010-0000-0000","12345","주소","상세");
        var fields=new ArrayList<CoupangCatalog.Field>();for(var k:List.of("originalPrice","maximumBuyForPerson","maximumBuyForPersonPeriod","outboundShippingTimeDay"))fields.add(new CoupangCatalog.Field(k,"0"));fields.add(new CoupangCatalog.Field("unitCount","1"));fields.add(new CoupangCatalog.Field("parallelImported","NOT_PARALLEL_IMPORTED"));fields.add(new CoupangCatalog.Field("overseasPurchased","NOT_OVERSEAS_PURCHASED"));fields.add(new CoupangCatalog.Field("pccNeeded","false"));
        var co=new CoupangOption(OPTION,null,null,fields,List.of(),List.of(new CoupangCatalog.Notice("패션잡화","소재","가죽")),List.of());
        var coupang=new Coupang(null,List.of(),List.of(new CoupangCatalog.Field("brandId","KR-5"),new CoupangCatalog.Field("saleStartedAt","2026-10-05T10:00:00"),new CoupangCatalog.Field("saleEndedAt","2026-10-06T10:00:00")),List.of(co),List.of(),null);
        return MarketplaceDocuments.normalize(new Document(null,null,common,List.of(option),StockMode.OPTION,"",List.of(),media,delivery,List.of(Market.COUPANG),Map.of(Market.COUPANG,new MarketConfig("123",null,coupang,null,null))));
    }
    private static CoupangEditor.CategoryRules rules(){return new CoupangEditor.CategoryRules("123",true,List.of(new CoupangEditor.AttributeRule("색상","EXPOSED","MANDATORY","NONE","STRING",null,List.of()),new CoupangEditor.AttributeRule("Manufacturer Part Number","NONE","MANDATORY","NONE","STRING",null,List.of())),List.of(new CoupangEditor.NoticeCategory("패션잡화",List.of(new CoupangEditor.NoticeRule("소재","MANDATORY")))),List.of(),List.of(),List.of("NEW"));}
    private static DraftValidation validator(){
        return new DraftValidation(coupangDocuments(editor(false)),mock(NaverProductDocuments.class),assets());
    }
    private static CoupangEditor editor(boolean fails){return new CoupangEditor(){public EditorDocument edit(Long actor,String id){throw new UnsupportedOperationException();}public CategoryRules category(Long actor,String code){assertThat(actor).isEqualTo(1L);assertThat(code).isEqualTo("123");if(fails)throw new MarketplaceFailure(MarketplaceFailure.Kind.RATE_LIMIT);return rules();}};}
    private static CoupangProductDocuments coupangDocuments(CoupangEditor editor){return ProviderCoupangDocumentsFixture.create(editor,mock(CoupangBrands.class));}
    private static ImageAssets assets(){return assets(500,500,100);}
    private static ImageAssets assets(int width,int height,long bytes){
        var assets=mock(ImageAssets.class);when(assets.metadata(anyLong(),anyString())).thenAnswer(i->{assertThat((Long)i.getArgument(0)).isEqualTo(1L);assertThat((String)i.getArgument(1)).isEqualTo(ASSET);return new ImageAssets.Asset(ASSET,"/api/marketplaces/assets/"+ASSET,"image/png",width,height,bytes);});return assets;
    }
    @Test void incompleteDraftCanBeNormalizedAndSerializedWithoutDefaultsPretendingToBeKnown(){
        var d=MarketplaceDocuments.normalize(blank());assertThat(d.options().getFirst().price()).isEmpty();assertThat(d.options().getFirst().quantity()).isEmpty();assertThat(d.common().name()).isEmpty();assertThat(d.delivery().charge()).isEmpty();
        var mapper=new ObjectMapper();assertThat(mapper.readValue(mapper.writeValueAsString(d),Document.class)).isEqualTo(d);
    }
    @Test void stableOptionIdsAndScopePreventServiceSelectionsFromCreatingStockRows(){
        var d=complete();var gift=new ServiceOption(UUID.randomUUID().toString(),"선물 포장",List.of("O","X"));
        var product=new Document(null,null,d.common(),d.options(),StockMode.PRODUCT,"9",List.of(gift),d.media(),d.delivery(),d.selectedMarkets(),d.markets());
        var normalized=MarketplaceDocuments.normalize(product);assertThat(normalized.options()).hasSize(1);assertThat(normalized.services()).hasSize(1);assertThat(normalized.productQuantity()).isEqualTo("9");assertThat(normalized.options().getFirst().id()).isEqualTo(OPTION);
        assertThat(validator().validate(1L,normalized,null).valid()).isTrue();
    }
    @Test void validCoupangDraftUsesCurrentMetadataAndZeroIsConfirmedInput(){
        var result=validator().validate(1L,complete(),null);assertThat(result.errors()).isEmpty();assertThat(result.unverified()).isEmpty();assertThat(result.valid()).isTrue();
    }
    @Test void urlOnlyImagesAreMarkedUnverifiedWithoutFetchingExternalHosts(){
        var d=complete();var url=new Image(IMAGE,null,"https://image.example.test/product.png",true,0,null);var input=new Document(null,null,d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(url),d.media().contents()),d.delivery(),d.selectedMarkets(),d.markets());
        var result=validator().validate(1L,input,null);assertThat(result.errors()).isEmpty();assertThat(result.unverified()).anyMatch(i->i.path().equals("media.images"));assertThat(result.valid()).isFalse();
    }
    @Test void explicitEmptyOverrideDoesNotFallBackToCommonNameOrPrice(){
        var d=complete();var m=d.markets().get(Market.COUPANG);var overrides=new Overrides("",null,null,List.of(new OptionOverride(OPTION,null,null,"",null)),null,null);
        var input=new Document(null,null,d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig("123",overrides,m.coupang(),null,null)));
        var result=validator().validate(1L,MarketplaceDocuments.normalize(input),null);assertThat(result.errors()).anyMatch(i->i.path().endsWith("overrides.name")).anyMatch(i->i.path().endsWith(".price"));
    }
    @Test void categoryFailureAndOtherMarketDynamicRulesCannotBeReportedAsValidationSuccess(){
        var result=new DraftValidation(coupangDocuments(editor(true)),mock(NaverProductDocuments.class),assets()).validate(1L,complete(),null);assertThat(result.errors()).anyMatch(i->i.path().endsWith("categoryCode"));assertThat(result.valid()).isFalse();
        var d=complete();var naver=new Document(null,null,d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),List.of(Market.NAVER),Map.of(Market.NAVER,new MarketConfig("123",null,null,null,null)));
        var result2=validator().validate(1L,naver,null);assertThat(result2.unverified()).anyMatch(i->i.market().equals("NAVER"));assertThat(result2.valid()).isFalse();
    }
    @Test void duplicateOrDanglingIdsAndUnsafeImageUrlsAreRejectedEvenDuringDraftSave(){
        var d=complete();var duplicate=new Document(null,null,d.common(),List.of(d.options().getFirst(),d.options().getFirst()),d.stockMode(),"",d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());assertThatThrownBy(()->MarketplaceDocuments.normalize(duplicate)).isInstanceOf(InputValidationFailure.class);
        for(String url:List.of("javascript:alert(1)","http://image.test/a","https://user:pass@image.test/a")){var input=new Document(null,null,d.common(),d.options(),d.stockMode(),"",d.services(),new Media(List.of(new Image(IMAGE,null,url,true,0,null)),List.of()),d.delivery(),d.selectedMarkets(),d.markets());assertThatThrownBy(()->MarketplaceDocuments.normalize(input)).isInstanceOf(InputValidationFailure.class);}
        var bad=new Document(null,null,d.common(),d.options(),d.stockMode(),"",d.services(),new Media(List.of(new Image(IMAGE,null,"https://image.test/a",true,0,UUID.randomUUID().toString())),List.of()),d.delivery(),d.selectedMarkets(),d.markets());assertThatThrownBy(()->MarketplaceDocuments.normalize(bad)).isInstanceOf(InputValidationFailure.class);
    }
    @Test void approvedImportPreservesCurrentUnknownAndProtectsExternalSourceAndOptionIdentities(){
        var source=new CoupangEditor.EditorDocument(new CoupangEditor.Basic("123","456","상품","노출명","제품","브랜드","그룹","123","승인완료"),new CoupangEditor.Limits(true,true,true),List.of(new CoupangEditor.EditOption("111","222","블랙",null,"미확인",true,List.of(new CoupangCatalog.Attribute("색상","블랙","EXPOSED")),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("externalVendorSku","SKU")),List.of())),List.of(),List.of(),List.of());
        var d=coupangDocuments(editor(false)).importObserved(source);assertThat(d.options()).hasSize(1);assertThat(d.options().getFirst().price()).isEmpty();assertThat(d.options().getFirst().quantity()).isEmpty();coupangDocuments(editor(false)).protectImport(d,d);
        var m=d.markets().get(Market.COUPANG);var changed=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig("999",m.overrides(),m.coupang(),null,null)));assertThatThrownBy(()->coupangDocuments(editor(false)).protectImport(d,changed)).isInstanceOf(InputValidationFailure.class);
        var o=d.options().getFirst();var hacked=new Option(o.id(),o.name(),o.sku(),o.price(),o.quantity(),List.of(new Attribute("색상","화이트")));var changed2=new Document(d.id(),d.revision(),d.common(),List.of(hacked),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());assertThatThrownBy(()->coupangDocuments(editor(false)).protectImport(d,changed2)).isInstanceOf(InputValidationFailure.class);
        var more=new Option(o.id(),o.name(),o.sku(),o.price(),o.quantity(),List.of(new Attribute("색상","블랙"),new Attribute("새 구매 속성","값")));var changed3=new Document(d.id(),d.revision(),d.common(),List.of(more),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());assertThatThrownBy(()->coupangDocuments(editor(false)).protectImport(d,changed3)).isInstanceOf(InputValidationFailure.class);
    }
    @Test void emptyImportedOptionsAreCategorizedAsExternalResponseFailure(){
        var empty=new CoupangEditor.EditorDocument(new CoupangEditor.Basic("123",null,"상품",null,null,null,null,"123",null),new CoupangEditor.Limits(true,true,true),List.of(),List.of(),List.of(),List.of());
        assertThatThrownBy(()->coupangDocuments(editor(false)).importObserved(empty)).isInstanceOfSatisfying(MarketplaceFailure.class,f->assertThat(f.kind()).isEqualTo(MarketplaceFailure.Kind.RESPONSE));
    }
    @Test void hugeAndNegativeNumbersAreRejectedAndSharedQuantityIsNotReplicatedIntoOptions(){
        var d=complete();var o=d.options().getFirst();var invalid=new Option(o.id(),o.name(),o.sku(),"9007199254740992","-1",o.attributes());var input=new Document(null,null,d.common(),List.of(invalid),StockMode.OPTION,"",d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());var result=validator().validate(1L,input,null);assertThat(result.errors()).anyMatch(i->i.path().equals("options.0.price")).anyMatch(i->i.path().equals("options.0.quantity"));
        var second=new Option(UUID.randomUUID().toString(),"화이트","SKU2","0","",List.of());var shared=new Document(null,null,d.common(),List.of(o,second),StockMode.PRODUCT,"9",d.services(),d.media(),d.delivery(),List.of(Market.NAVER),Map.of(Market.NAVER,new MarketConfig("123",null,null,null,null)));var result2=validator().validate(1L,shared,null);assertThat(result2.unverified()).anyMatch(i->i.path().equals("stockMode"));assertThat(shared.options().get(1).quantity()).isEmpty();
    }
    private static Document withMarket(Market market,String price,String quantity,StockMode mode){
        var d=complete();var o=d.options().getFirst();var option=new Option(o.id(),o.name(),o.sku(),price,quantity,o.attributes());
        var naver=market==Market.NAVER?new Naver("","SALE","NEW","00","","010-0000-0000",List.of(),List.of(),null,false,"ON"):null;
        var esm=market==Market.GMARKET||market==Market.AUCTION?new Esm(market==Market.GMARKET?"2":"1","","","","",List.of(),List.of()):null;
        var config=market==Market.COUPANG?d.markets().get(market):new MarketConfig("123",null,null,naver,esm);
        return MarketplaceDocuments.normalize(new Document(null,null,d.common(),List.of(option),mode,mode==StockMode.PRODUCT?quantity:"",d.services(),d.media(),d.delivery(),List.of(market),Map.of(market,config)));
    }
    @Test void naverUsesItsOwnLargeStockAndPriceLimitsRatherThanCoupangLimits(){
        var naver=withMarket(Market.NAVER,"999999990","100000",StockMode.OPTION);var result=validator().validate(1L,naver,null);assertThat(result.errors()).isEmpty();assertThat(result.unverified()).isNotEmpty();
        assertThat(validator().validate(1L,withMarket(Market.NAVER,"999999991","99999999",StockMode.PRODUCT),null).errors()).anyMatch(i->i.path().endsWith(".price"));
        assertThat(validator().validate(1L,withMarket(Market.NAVER,"100","100000000",StockMode.PRODUCT),null).errors()).anyMatch(i->i.path().equals("productQuantity"));
        assertThat(validator().validate(1L,withMarket(Market.COUPANG,"100","100000",StockMode.OPTION),null).errors()).anyMatch(i->i.market()!=null&&i.market().equals("COUPANG")&&i.path().endsWith(".quantity"));
    }
    @Test void esmChecksPriceStepsAndProductStockButKeepsZeroOptionStateMappingSeparate(){
        for(String price:List.of("0","9","11","1000000000"))assertThat(validator().validate(1L,withMarket(Market.GMARKET,price,"1",StockMode.PRODUCT),null).errors()).anyMatch(i->i.path().endsWith(".price"));
        for(String quantity:List.of("0","100000"))assertThat(validator().validate(1L,withMarket(Market.AUCTION,"10",quantity,StockMode.PRODUCT),null).errors()).anyMatch(i->i.path().equals("productQuantity"));
        var d=withMarket(Market.GMARKET,"10","1",StockMode.OPTION);var o=d.options().getFirst();var second=new Option(UUID.randomUUID().toString(),"화이트","SKU2","10","0",List.of());var input=new Document(null,null,d.common(),List.of(o,second),StockMode.OPTION,"",List.of(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());var result=validator().validate(1L,input,null);
        assertThat(result.errors()).noneMatch(i->i.path().equals("options")||i.path().endsWith(".quantity"));assertThat(result.unverified()).anyMatch(i->i.path().endsWith(".quantity"));
    }
    @Test void esmRepresentativeAssetsHaveDocumentedDimensionsAndSizeWhileUrlsStayUnverified(){
        var editor=editor(false);var assets=assets(600,700,2_000_000);
        var input=withMarket(Market.AUCTION,"999999990","99999",StockMode.PRODUCT);var result=new DraftValidation(coupangDocuments(editor),mock(NaverProductDocuments.class),assets).validate(1L,input,null);assertThat(result.errors()).isEmpty();
        var tooLarge=assets(600,600,2_000_001);
        assertThat(new DraftValidation(coupangDocuments(editor),mock(NaverProductDocuments.class),tooLarge).validate(1L,input,null).errors()).anyMatch(i->i.path().equals("media.images"));assertThat(validator().validate(1L,input,null).errors()).anyMatch(i->i.path().equals("media.images"));
        var url=new Image(IMAGE,null,"https://image.example.test/product.png",true,0,null);var urlInput=new Document(null,null,input.common(),input.options(),input.stockMode(),input.productQuantity(),input.services(),new Media(List.of(url),input.media().contents()),input.delivery(),input.selectedMarkets(),input.markets());assertThat(validator().validate(1L,urlInput,null).unverified()).anyMatch(i->i.path().equals("media.images"));
    }
    @Test void naverChannelSettingsPreserveMissingAndFalseAndRejectResponseOnlyStates(){
        var d=withMarket(Market.NAVER,"100","100000",StockMode.OPTION);assertThat(d.markets().get(Market.NAVER).naver().naverShoppingRegistration()).isFalse();
        var m=d.markets().get(Market.NAVER);var bad=new Naver("","WAIT","OTHER","","","",List.of(),List.of(),"전용명",null,"WAIT");var changed=new Document(null,null,d.common(),d.options(),d.stockMode(),"",List.of(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.NAVER,new MarketConfig("123",null,null,bad,null)));
        var normalized=MarketplaceDocuments.normalize(changed);assertThat(normalized.markets().get(Market.NAVER).naver().channelProductName()).isEqualTo("전용명");assertThat(normalized.markets().get(Market.NAVER).naver().naverShoppingRegistration()).isNull();
        var errors=validator().validate(1L,normalized,null).errors();for(String field:List.of("status","saleType","originCode","afterServiceTelephone","naverShoppingRegistration","channelProductDisplayStatusType"))assertThat(errors).anyMatch(i->i.path().endsWith(".naver."+field));
    }
    @Test void unselectedMarketInvalidNumbersStayInPartialDraftWithoutBeingValidated(){
        var d=withMarket(Market.NAVER,"100","100000",StockMode.OPTION);var invalid=new Overrides(null,null,null,List.of(new OptionOverride(OPTION,null,null,"잘못된 가격","-1")),null,null);var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());markets.put(Market.COUPANG,new MarketConfig("",invalid,null,null,null));
        var input=MarketplaceDocuments.normalize(new Document(null,null,d.common(),d.options(),d.stockMode(),"",d.services(),d.media(),d.delivery(),d.selectedMarkets(),markets));assertThat(input.markets().get(Market.COUPANG).overrides().options().getFirst().price()).isEqualTo("잘못된 가격");assertThat(validator().validate(1L,input,null).errors()).noneMatch(i->"COUPANG".equals(i.market()));
    }
    @Test void createRejectsDuplicatePurchaseCombinationEvenWithDistinctNamesAndSearchFilters(){
        var mapper=new ObjectMapper();var tree=mapper.valueToTree(complete());
        var first=tree.path("options").get(0).deepCopy().asObject();var second=first.deepCopy();
        String secondId="00000000-0000-4000-8000-000000000011";second.put("id",secondId);second.put("name","별도 등록 옵션명");second.put("sku","SKU2");tree.path("options").asArray().add(second);
        var config=tree.path("markets").path("COUPANG").path("coupang").path("options");
        var attributes=mapper.createArrayNode();attributes.add(mapper.createObjectNode().put("name","색상").put("value","블랙").put("exposed","EXPOSED"));attributes.add(mapper.createObjectNode().put("name","Manufacturer Part Number").put("value","MPN1").put("exposed","NONE"));config.get(0).asObject().set("attributes",attributes);
        var next=config.get(0).deepCopy().asObject();next.put("optionId",secondId);next.path("attributes").get(1).asObject().put("value","MPN2");config.asArray().add(next);
        var duplicate=mapper.treeToValue(tree,Document.class);
        assertThat(validator().validate(1L,duplicate,null).errors()).anyMatch(issue->issue.message().contains("구매 옵션 조합이 중복"));
        next.path("attributes").get(0).asObject().put("value","화이트");
        assertThat(validator().validate(1L,mapper.treeToValue(tree,Document.class),null).errors()).noneMatch(issue->issue.message().contains("구매 옵션 조합이 중복"));
    }
    @Test void createAutomaticMinimumPriceUsesEffectiveOptionSalePrice(){
        var mapper=new ObjectMapper();var tree=mapper.valueToTree(complete());tree.path("options").get(0).asObject().put("price","100");
        var registration=tree.path("markets").path("COUPANG").path("coupang").path("options").get(0).path("registration").asArray();
        var minimum=mapper.createObjectNode().put("name","autoPricingInfo.minSalePrice").put("value","100");registration.add(minimum);
        assertThat(validator().validate(1L,mapper.treeToValue(tree,Document.class),null).errors()).anyMatch(issue->issue.path().endsWith("autoPricingInfo.minSalePrice"));
        minimum.put("value","90");assertThat(validator().validate(1L,mapper.treeToValue(tree,Document.class),null).errors()).noneMatch(issue->issue.path().endsWith("autoPricingInfo.minSalePrice"));
        minimum.put("value","-1");assertThat(validator().validate(1L,mapper.treeToValue(tree,Document.class),null).errors()).anyMatch(issue->issue.path().endsWith("autoPricingInfo.minSalePrice"));
    }
}
