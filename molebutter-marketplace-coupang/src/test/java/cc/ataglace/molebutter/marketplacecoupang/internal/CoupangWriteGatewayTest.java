package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditConflict;
import cc.ataglace.molebutter.media.api.ImageAssets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.*;

class CoupangWriteGatewayTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Instant NOW=Instant.parse("2026-10-06T00:00:00Z");
    static final String BASE="""
        {"sellerProductId":123,"vendorId":"A-test","sellerProductName":"상품","displayProductName":"상품","generalProductName":"제품","brand":"브랜드","displayCategoryCode":10,"statusName":"승인완료","vendorUserId":"fake-wing","manufacture":"제조사","requested":false,
        "saleStartedAt":"2026-10-01T00:00:00","saleEndedAt":"2099-12-31T23:59:59","future":{"preserve":[1,2]},
        "deliveryMethod":"SEQUENCIAL","deliveryCompanyCode":"CJGLS","deliveryChargeType":"FREE","deliveryCharge":0,"freeShipOverAmount":0,"deliveryChargeOnReturn":3000,"returnCharge":3000,"remoteAreaDeliverable":"Y","unionDeliveryType":"NOT_UNION_DELIVERY","outboundShippingPlaceCode":123,"returnCenterCode":"456","returnChargeName":"반품지","companyContactNumber":"000-0000-0000","returnZipCode":"00000","returnAddress":"주소","returnAddressDetail":"상세",
        "items":[{"sellerProductItemId":111,"vendorItemId":222,"itemName":"블랙","externalVendorSku":"SKU-A","originalPrice":100,"salePrice":100,"maximumBuyCount":9,"maximumBuyForPerson":0,"maximumBuyForPersonPeriod":0,"outboundShippingTimeDay":1,"unitCount":1,"adultOnly":"EVERYONE","taxType":"TAX","parallelImported":"NOT_PARALLEL_IMPORTED","overseasPurchased":"NOT_OVERSEAS_PURCHASED","pccNeeded":false,"futureItem":{"keep":true},"attributes":[{"attributeTypeName":"색상","attributeValueName":"블랙","exposed":"EXPOSED","futureAttr":"keep"}],"images":[{"imageOrder":0,"imageType":"REPRESENTATION","cdnPath":"test/sample.jpg","futureImage":"keep"}],"contents":[{"contentsType":"HTML","futureContent":"keep","contentDetails":[{"detailType":"TEXT","content":"본문"}]}]}]}
        """;
    final CoupangProductClient client=mock(CoupangProductClient.class);
    final ImageAssets assets=mock(ImageAssets.class);
    final DefaultCoupangWriteGateway writer=new DefaultCoupangWriteGateway(mock(BusinessAccess.class),client,assets,JSON,mock(cc.ataglace.molebutter.marketplace.api.CoupangBrands.class));
    CoupangWriteGatewayTest(){
        when(client.accountKey()).thenReturn("account");when(client.vendorId()).thenReturn("A-test");when(client.now()).thenReturn(NOW);
        when(client.writeSession(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
        when(client.rawProduct("123")).thenAnswer(i->JSON.readTree(BASE));
        when(client.rawInventory("222")).thenReturn(JSON.readTree("{\"salePrice\":100,\"amountInStock\":9,\"onSale\":true}"));
        when(client.rawInventory(anyString(),anyBoolean(),anyBoolean())).thenAnswer(i->client.rawInventory(i.getArgument(0)));
        when(client.rawSummary(anyString())).thenReturn(JSON.createArrayNode());
        var parser=new CoupangProductClient("A-test","fake-access","fake-secret",JSON);
        when(client.editorDocument(any(),anyMap(),anyMap())).thenAnswer(i->parser.editorDocument(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
    }
    Document imported(boolean approved){
        var raw=(ObjectNode)JSON.readTree(BASE);if(!approved)raw.path("items").get(0).asObject().putNull("vendorItemId");
        var parser=new CoupangProductClient("A-test","fake-access","fake-secret",JSON);
        var envelope=JSON.createObjectNode();envelope.put("code","SUCCESS");envelope.set("data",raw);var detail=parser.parseDetail(JSON.writeValueAsBytes(envelope),"123");var p=detail.product();var i=detail.items().getFirst();
        var current=approved?new CoupangCatalog.CurrentInventory("222",100L,9L,true):null;
        var option=new CoupangEditor.EditOption(i.sellerProductItemId(),i.vendorItemId(),i.itemName(),current,null,approved,i.attributes(),i.images(),i.contents(),i.notices(),i.settings(),i.certifications());
        return DraftCoupangImport.convert(new CoupangEditor.EditorDocument(new CoupangEditor.Basic("123",null,p.sellerProductName(),detail.displayProductName(),detail.generalProductName(),p.brand(),null,"10",p.statusName()),new CoupangEditor.Limits(true,true,true),List.of(option),detail.delivery(),detail.settings(),List.of()));
    }
    Document option(Document d,String price,String quantity){var o=d.options().getFirst();return new Document(d.id(),d.revision(),d.common(),List.of(new Option(o.id(),o.name(),o.sku(),price,quantity,o.attributes())),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());}
    Document setting(Document d,String name,String value){
        var m=d.markets().get(Market.COUPANG);var c=m.coupang();var values=new ArrayList<>(c.settings());values.removeIf(f->name.equals(f.name()));values.add(new CoupangCatalog.Field(name,value));
        var cfg=new Coupang(c.sellerProductId(),c.delivery(),values,c.options(),c.documents(),c.source());
        return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(m.categoryCode(),m.overrides(),cfg,null,null)));
    }
    Document fresh(){
        var d=option(imported(false),"100","9");var m=d.markets().get(Market.COUPANG);var c=m.coupang();var o=c.options().getFirst();
        var settings=new ArrayList<>(c.settings());settings.removeIf(f->"vendorUserId".equals(f.name())||"brandId".equals(f.name()));settings.add(new CoupangCatalog.Field("vendorUserId","fake-wing"));settings.add(new CoupangCatalog.Field("brandId","KR-5"));
        var cfg=new Coupang(null,c.delivery(),settings,List.of(new CoupangOption(o.optionId(),null,null,o.registration(),o.attributes(),o.notices(),o.certifications())),c.documents(),null);
        return new Document(null,null,d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(m.categoryCode(),m.overrides(),cfg,null,null)));
    }
    @Test void dedicatedRegistrationConversionProducesSingleCreateWithApprovalAndNoExternalIdentity(){
        var input=CoupangRegistrationInput.input(fresh());var document=CoupangRegistrationInput.convert(input);
        var prepared=writer.prepare(1L,document,null,true);assertThat(prepared.steps()).hasSize(1);var step=prepared.steps().getFirst();
        assertThat(step.type()).isEqualTo(Type.CREATE);assertThat(step.method()).isEqualTo("POST");
        var body=JSON.readTree(step.bodyJson());assertThat(body.path("requested").asBoolean()).isTrue();assertThat(body.path("brand").asString()).isEqualTo("브랜드");
        assertThat(body.has("sellerProductId")).isFalse();var item=body.path("items").get(0);assertThat(item.has("sellerProductItemId")).isFalse();assertThat(item.has("vendorItemId")).isFalse();
        assertThat(item.path("salePrice").asLong()).isEqualTo(100);assertThat(item.path("maximumBuyCount").asLong()).isEqualTo(9);assertThat(item.path("itemName").asString()).isEqualTo("블랙");
        assertThat(item.path("images").get(0).path("imageType").asString()).isEqualTo("REPRESENTATION");assertThat(item.path("contents").get(0).path("contentsType").asString()).isEqualTo("HTML");
    }
    @Test void approvedOriginalPriceUsesDedicatedPathAndReadbackIncludingZero(){
        var d=imported(true);String oid=d.options().getFirst().id();
        var changes=List.of(new MarketplaceEditing.Change("markets.COUPANG.coupang.options.registration.originalPrice",oid,"0"));
        var mapping=new Mapping("account","123",List.of(new OptionMapping(oid,"111","222")));
        var p=writer.prepareSelected(1L,d,mapping,true,d,changes);var step=p.steps().getFirst();
        assertThat(p.steps()).extracting(Step::type).containsExactly(Type.ORIGINAL_PRICE);
        assertThat(step.path()).endsWith("/222/original-prices/0");assertThat(step.query()).isEmpty();assertThat(step.bodyJson()).isNull();
        var accepted=writer.parseOutcome("{\"code\":\"SUCCESS\",\"data\":null}".getBytes(),step,mapping,NOW);
        assertThat(accepted.state()).isEqualTo(State.ACCEPTED);
        assertThat(writer.reconcile(1L,p,step,accepted).state()).isEqualTo(State.ACCEPTED);
        var applied=JSON.readTree(BASE).deepCopy().asObject();applied.path("items").get(0).asObject().put("originalPrice",0);when(client.rawProduct("123")).thenReturn(applied);
        assertThat(writer.reconcile(1L,p,step,accepted).state()).isEqualTo(State.CONFIRMED);
        verify(client,never()).rawInventory(anyString(),anyBoolean(),anyBoolean());
    }
    @Test void mixedChangesAreOrderedAndPreserveApprovalFlagOnlyOnProduct(){
        var d=imported(true);String oid=d.options().getFirst().id();var mapping=new Mapping("account","123",List.of(new OptionMapping(oid,"111","222")));
        var p=writer.prepareSelected(1L,d,mapping,true,d,List.of(
            new MarketplaceEditing.Change("common.name",null,"새 상품"),new MarketplaceEditing.Change("delivery.returnCharge",null,"4000"),
            new MarketplaceEditing.Change("markets.COUPANG.coupang.options.registration.originalPrice",oid,"200"),
            new MarketplaceEditing.Change("options.price",oid,"0"),new MarketplaceEditing.Change("options.quantity",oid,"0")));
        assertThat(p.steps()).extracting(Step::type).containsExactly(Type.DELIVERY,Type.ORIGINAL_PRICE,Type.PRICE,Type.STOCK,Type.PRODUCT);
        var body=JSON.readTree(p.steps().getLast().bodyJson());assertThat(body.path("requested").asBoolean()).isTrue();
        assertThat(body.path("items").get(0).path("originalPrice").asInt()).isEqualTo(100);assertThat(body.path("items").get(0).path("salePrice").asInt()).isEqualTo(100);
        assertThat(JSON.readTree(p.steps().getFirst().bodyJson()).has("requested")).isFalse();
        assertThatThrownBy(()->writer.prepareSelected(1L,d,mapping,true,d,List.of(new MarketplaceEditing.Change("markets.COUPANG.coupang.options.registration.originalPrice",oid,"11")))).isInstanceOf(InputValidationFailure.class);
    }
    @Test void productReadbackWaitsForApprovalEvenWhenContentAlreadyVisible(){
        var d=imported(true);String oid=d.options().getFirst().id();var mapping=new Mapping("account","123",List.of(new OptionMapping(oid,"111","222")));
        var p=writer.prepareSelected(1L,d,mapping,true,d,List.of(new MarketplaceEditing.Change("common.name",null,"새 상품")));var step=p.steps().getFirst();
        var raw=JSON.readTree(step.bodyJson()).deepCopy().asObject();raw.put("statusName","승인대기중");when(client.rawProduct("123")).thenReturn(raw);
        var accepted=writer.parseOutcome("{\"code\":\"SUCCESS\"}".getBytes(),step,mapping,NOW);
        assertThat(writer.reconcile(1L,p,step,accepted).state()).isEqualTo(State.ACCEPTED);
        raw.put("statusName","승인완료");raw.remove("requested");assertThat(writer.reconcile(1L,p,step,accepted).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void uploadedMediaPublishesOwnedAssetAndPreservesOtherImageTypes(){
        var d=imported(true);String oid=d.options().getFirst().id(),asset=UUID.randomUUID().toString();var mapping=new Mapping("account","123",List.of(new OptionMapping(oid,"111","222")));
        when(assets.metadata(1L,asset)).thenReturn(new ImageAssets.Asset(asset,"/api/marketplaces/assets/"+asset,"image/png",500,500,100));when(assets.submissionUrl(1L,asset)).thenReturn("https://images.example.com/public-token");
        var original=d.media().images().getFirst();
        var images=List.of(new Image(UUID.randomUUID().toString(),asset,"/api/marketplaces/assets/"+asset,true,0,oid,"REPRESENTATION"),new Image(original.id(),null,original.url(),false,1,oid,"DETAIL"),new Image(UUID.randomUUID().toString(),null,"https://images.example.com/used.jpg",false,2,oid,"USED_PRODUCT"));
        var p=writer.prepareSelected(1L,d,mapping,true,d,List.of(new MarketplaceEditing.Change("media.images",oid,images)));
        var body=JSON.readTree(p.steps().getFirst().bodyJson());var rows=body.path("items").get(0).path("images");
        assertThat(rows.get(0).path("vendorPath").asString()).isEqualTo("https://images.example.com/public-token");
        assertThat(rows.get(1).path("futureImage").asString()).isEqualTo("keep");assertThat(rows.get(1).path("imageType").asString()).isEqualTo("DETAIL");
        assertThat(rows.get(2).path("imageType").asString()).isEqualTo("USED_PRODUCT");assertThat(p.steps().getFirst().bodyJson()).doesNotContain("blob:","/api/marketplaces/assets/");
    }
    @Test void noticeCategorySelectionExcludesInactiveRowsAndConflictsOnNewExternalType(){
        var reference=imported(true);String oid=reference.options().getFirst().id();var mapping=new Mapping("account","123",List.of(new OptionMapping(oid,"111","222")));
        var raw=JSON.readTree(BASE).deepCopy().asObject();raw.path("items").get(0).asObject().set("notices",JSON.readTree("[{\"noticeCategoryName\":\"가방\",\"noticeCategoryDetailName\":\"종류\",\"content\":\"가방\"},{\"noticeCategoryName\":\"기타\",\"noticeCategoryDetailName\":\"품명\",\"content\":\"기타\"}]"));
        when(client.rawProduct("123")).thenReturn(raw);
        var observed=CoupangEditingProjection.latest(reference,mapping,client.editorDocument(raw,Map.of("222",new CoupangCatalog.CurrentInventory("222",100L,9L,true)),Map.of()));
        var selected=List.of(new MarketplaceEditing.Change("markets.COUPANG.coupang.options.notices",oid,List.of(new CoupangCatalog.Notice("가방","종류","상품 상세페이지 참조"))));
        var prepared=writer.prepareSelected(1L,reference,mapping,true,observed,selected);
        var notices=JSON.readTree(prepared.steps().getFirst().bodyJson()).path("items").get(0).path("notices");
        assertThat(notices.size()).isEqualTo(1);assertThat(notices.get(0).path("noticeCategoryName").asString()).isEqualTo("가방");
        raw.path("items").get(0).path("notices").asArray().add(JSON.readTree("{\"noticeCategoryName\":\"외부 추가\",\"noticeCategoryDetailName\":\"새 항목\",\"content\":\"값\"}"));
        assertThatThrownBy(()->writer.prepareSelected(1L,reference,mapping,true,observed,selected)).isInstanceOf(MarketplaceEditConflict.class);
    }
    @Test void unchangedImportMakesNoWriteAndPreservesUnknowns(){
        var p=writer.prepare(1L,imported(true),null,false);
        assertThat(p.steps()).isEmpty();verify(client,never()).write(any(),any(),any(),any());
        var raw=JSON.readTree(BASE).deepCopy().asObject();raw.put("brandId","KR-5");when(client.rawProduct("123")).thenReturn(raw);
        assertThat(writer.prepare(1L,setting(imported(true),"brandId","KR-5"),null,false).steps()).isEmpty();
    }
    @Test void currentPriceAndStockAreSeparateAndPreviewShowsNumbers(){
        var d=option(imported(true),"170","8");var p=writer.prepare(1L,d,null,false);
        assertThat(p.steps()).extracting(Step::type).containsExactly(Type.PRICE,Type.STOCK);
        assertThat(p.steps().getFirst().path()).endsWith("/222/prices/170");assertThat(p.steps().getFirst().query()).isEqualTo("forceSalePriceUpdate=false");
        assertThat(p.changes()).anyMatch(c->"100".equals(c.before())&&"170".equals(c.after())).anyMatch(c->"9".equals(c.before())&&"8".equals(c.after()));
    }
    @Test void pendingExplicitPriceWinsOverImportedRegistrationAndUnknownsStayInWire(){
        var raw=(ObjectNode)JSON.readTree(BASE);raw.path("items").get(0).asObject().putNull("vendorItemId");when(client.rawProduct("123")).thenReturn(raw);
        var p=writer.prepare(1L,option(imported(false),"170","8"),null,false);var step=p.steps().getFirst();
        assertThat(step.type()).isEqualTo(Type.PRODUCT);var body=JSON.readTree(step.bodyJson());
        assertThat(body.path("items").get(0).path("salePrice").asInt()).isEqualTo(170);assertThat(body.path("items").get(0).path("maximumBuyCount").asInt()).isEqualTo(8);
        assertThat(body.path("future").path("preserve").size()).isEqualTo(2);assertThat(step.bodyJson()).contains("futureItem","futureAttr","futureImage","futureContent");
        assertThat(p.changes()).anyMatch(c->c.path().contains("salePrice")&&"170".equals(c.after()));
    }
    @Test void createWireBoundaryRejectsDuplicatePurchaseAttributesBeforePreparingPost(){
        var tree=JSON.valueToTree(fresh());var options=tree.path("options").asArray();var second=options.get(0).deepCopy().asObject();
        String secondId=UUID.randomUUID().toString();second.put("id",secondId);second.put("name","다른 등록 옵션명");second.put("sku","SKU-B");options.add(second);
        var configured=tree.path("markets").path("COUPANG").path("coupang").path("options").asArray();var secondConfig=configured.get(0).deepCopy().asObject();secondConfig.put("optionId",secondId);configured.add(secondConfig);
        assertThatThrownBy(()->writer.prepare(1L,JSON.treeToValue(tree,Document.class),null,true)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("구매 옵션 조합이 중복");
        verify(client,never()).write(any(),any(),any(),any());verify(client,never()).rawSummary(any());
    }
    @Test void createWireBoundaryRejectsAutomaticMinimumAtOrAboveEffectivePrice(){
        var tree=JSON.valueToTree(fresh());var fields=tree.path("markets").path("COUPANG").path("coupang").path("options").get(0).path("registration").asArray();
        var minimum=JSON.createObjectNode().put("name","autoPricingInfo.minSalePrice").put("value","100");fields.add(minimum);
        assertThatThrownBy(()->writer.prepare(1L,JSON.treeToValue(tree,Document.class),null,true)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("자동 가격 최소 판매가");
        minimum.put("value","90");assertThat(writer.prepare(1L,JSON.treeToValue(tree,Document.class),null,true).steps()).extracting(Step::type).containsExactly(Type.CREATE);
        verify(client,never()).write(any(),any(),any(),any());
    }
    @Test void createDefaultsToSavedStateAndSkusAreEncodedOnlyForWireLookup(){
        var p=writer.prepare(1L,fresh(),null,false);assertThat(p.steps()).extracting(Step::type).containsExactly(Type.CREATE);
        assertThat(JSON.readTree(p.steps().getFirst().bodyJson()).path("requested").asBoolean()).isFalse();assertThat(p.expectedSkus()).containsExactly("SKU-A");
        assertThat(p.changes()).anyMatch(c->c.path().contains("salePrice")&&"100".equals(c.after())).anyMatch(c->c.path().contains("maximumBuyCount")&&"9".equals(c.after()));
        assertThat(JSON.readTree(p.steps().getFirst().bodyJson()).path("items").get(0).path("contents").get(0).path("contentDetails").get(0).path("detailType").asString()).isEqualTo("TEXT");
        assertThat(JSON.readTree(writer.prepare(1L,fresh(),null,true).steps().getFirst().bodyJson()).path("requested").asBoolean()).isTrue();
        assertThat(JSON.readTree(writer.prepare(1L,setting(fresh(),"brandId","KR-5"),null,false).steps().getFirst().bodyJson()).path("brandId").asString()).isEqualTo("KR-5");
    }
    @Test void flatWrappedWarningAndBadResponsesRemainDistinct(){
        var p=writer.prepare(1L,fresh(),null,false);var s=p.steps().getFirst();
        assertThat(writer.parseOutcome("{\"code\":\"200\",\"data\":{\"code\":\"SUCCESS\",\"data\":99999999999999999999}}".getBytes(),s,p.mapping(),NOW).mapping().sellerProductId()).isEqualTo("99999999999999999999");
        var warning=writer.parseOutcome("{\"code\":\"SUCCESS\",\"data\":555,\"errorItems\":[{}],\"details\":\"raw-private-value\"}".getBytes(),s,p.mapping(),NOW);
        assertThat(warning.state()).isEqualTo(State.ACCEPTED);assertThat(warning.code()).isEqualTo("NEEDS_CORRECTION");assertThat(warning.message()).doesNotContain("raw-private-value");
        assertThat(writer.parseOutcome("{\"code\":\"200\",\"data\":{\"code\":\"SUCCESS\",\"data\":555,\"details\":[{\"errorItems\":[{}]}]}}".getBytes(),s,p.mapping(),NOW).code()).isEqualTo("NEEDS_CORRECTION");
        for(String bad:List.of("bad-json","{\"code\":\"SUCCESS\"}","{\"code\":\"200\",\"data\":{}}"))assertThat(writer.parseOutcome(bad.getBytes(),s,p.mapping(),NOW).state()).isEqualTo(State.UNKNOWN);
        assertThat(writer.parseOutcome("{\"code\":\"ERROR\"}".getBytes(),s,p.mapping(),NOW).state()).isEqualTo(State.FAILED);
    }
    @Test void documentedPriceRejectionsKeepSafeReasonForHttp400AndHttp200(){
        var reasons=Map.of("최대 50% 인하/최대 100%인상","PRICE_CHANGE_RANGE","자동생성옵션의 가격을 직접 수정할 수 없습니다.","AUTOMATIC_OPTION","삭제된 상품은 변경이 불가능합니다.","DELETED_OPTION","가격은 최소 10원 단위로 입력가능합니다.","PRICE_UNIT","유효하지 않은 ID입니다.","INVALID_OPTION_ID","apMinSalePrice must be less than price","AUTO_PRICE_MINIMUM","apMinSalePrice and apActive must be provided together","AUTO_PRICE_PAIR");
        var p=writer.prepare(1L,fresh(),null,false);var step=p.steps().getFirst();
        for(var reason:reasons.entrySet()){
            var bytes=JSON.writeValueAsBytes(Map.of("code","ERROR","message",reason.getKey()+" private-contact@example.test"));
            var failed=writer.httpFailure(400,bytes,p.mapping(),NOW);
            assertThat(failed.state()).isEqualTo(State.FAILED);assertThat(failed.code()).isEqualTo(reason.getValue());
            assertThat(CoupangWriteRejection.message(failed.code())).isEqualTo(failed.message()).doesNotContain("private-contact");
            assertThat(writer.parseOutcome(bytes,step,p.mapping(),NOW).code()).isEqualTo(reason.getValue());
        }
    }
    @Test void unknownRejectionRetainsHttpStatusWithoutExposingResponseAndServerErrorsStayAmbiguous(){
        var p=writer.prepare(1L,fresh(),null,false);var body="{\"message\":\"unclassified private-contact@example.test\"}".getBytes();
        var failed=writer.httpFailure(400,body,p.mapping(),NOW);
        assertThat(failed.code()).isEqualTo("HTTP_400_REJECTED");assertThat(failed.message()).contains("HTTP 400").doesNotContain("private-contact");
        assertThat(writer.httpFailure(403,body,p.mapping(),NOW).code()).isEqualTo("PERMISSION");
        assertThat(writer.httpFailure(500,body,p.mapping(),NOW).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void conflictPreventsWriteAndAccountChangePreventsEvenReading(){
        var p=writer.prepare(1L,option(imported(true),"170","8"),null,false);when(client.rawInventory("222")).thenReturn(JSON.readTree("{\"salePrice\":110,\"amountInStock\":9}"));
        var result=writer.execute(1L,p,p.steps().getFirst(),p.mapping());assertThat(result.code()).isEqualTo("BASELINE_CHANGED");verify(client,never()).write(any(),any(),any(),any());
        assertThatThrownBy(()->writer.prepare(1L,setting(imported(true),"brandId","KR-5"),null,false)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("브랜드 ID");
        var raw=JSON.readTree(BASE).deepCopy().asObject();raw.put("brandId","KR-5");when(client.rawProduct("123")).thenReturn(raw);
        for(String changed:Arrays.asList("KR-6","",null))assertThatThrownBy(()->writer.prepare(1L,setting(imported(true),"brandId",changed),null,false)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("브랜드 ID");
        assertThatThrownBy(()->writer.prepare(1L,setting(fresh(),"brandId","KR-6"),p.mapping(),false)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("브랜드 ID");
        when(client.accountKey()).thenReturn("another");assertThat(writer.execute(1L,p,p.steps().getFirst(),p.mapping()).code()).isEqualTo("ACCOUNT_CHANGED");
    }
    @Test void createSuccessRetainsIdIfVerificationCannotRead(){
        var p=writer.prepare(1L,fresh(),null,false);when(client.write(any(),any(),any(),any())).thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes()));
        when(client.rawProduct("555")).thenThrow(new MarketplaceFailure(MarketplaceFailure.Kind.NETWORK));
        var r=writer.execute(1L,p,p.steps().getFirst(),p.mapping());assertThat(r.state()).isEqualTo(State.ACCEPTED);assertThat(r.mapping().sellerProductId()).isEqualTo("555");
    }
    @Test void requestedCreateWaitsForApprovalEvenWhenEverySubmittedValueIsVisible(){
        var prepared=writer.prepare(1L,fresh(),null,true);var step=prepared.steps().getFirst();
        var actual=JSON.readTree(step.expectedJson()).deepCopy().asObject();actual.put("sellerProductId",555);actual.put("statusName","승인대기중");actual.path("items").get(0).asObject().put("sellerProductItemId",111);
        when(client.rawProduct("555")).thenReturn(actual);
        when(client.write(any(),any(),any(),any())).thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\",\"data\":555}".getBytes()));
        var pending=writer.execute(1L,prepared,step,prepared.mapping());
        assertThat(pending.state()).isEqualTo(State.ACCEPTED);assertThat(pending.mapping().sellerProductId()).isEqualTo("555");
        assertThat(pending.mapping().options().getFirst().sellerProductItemId()).isEqualTo("111");
        actual.put("statusName","승인완료");actual.remove("requested");
        assertThat(writer.reconcile(1L,prepared,step,pending).state()).isEqualTo(State.CONFIRMED);
        verify(client,times(1)).write(any(),any(),any(),any());
    }
    @Test void lostCreateResponseCanRecoverIdentityWhileApprovalIsStillPending(){
        var prepared=writer.prepare(1L,fresh(),null,true);var step=prepared.steps().getFirst();
        var actual=JSON.readTree(step.expectedJson()).deepCopy().asObject();actual.put("sellerProductId",555);actual.put("statusName","심사중");actual.path("items").get(0).asObject().put("sellerProductItemId",111);
        when(client.now()).thenReturn(NOW.plusSeconds(60));when(client.rawSummary("SKU-A")).thenReturn(JSON.readTree("[{\"sellerProductId\":555}]"));when(client.rawProduct("555")).thenReturn(actual);
        var pending=writer.reconcile(1L,prepared,step,new Result(State.UNKNOWN,prepared.mapping(),"TIMEOUT","확인 중",NOW));
        assertThat(pending.state()).isEqualTo(State.ACCEPTED);assertThat(pending.mapping().sellerProductId()).isEqualTo("555");
        actual.put("statusName","APPROVED");
        var confirmed=writer.reconcile(1L,prepared,step,pending);assertThat(confirmed.state()).isEqualTo(State.CONFIRMED);assertThat(confirmed.message()).doesNotContain("기다리고");
        verify(client,never()).write(any(),any(),any(),any());
    }
    @Test void unknownCreateWaitsOneMinuteAndNeverPostsAgain(){
        var p=writer.prepare(1L,fresh(),null,false);var unknown=new Result(State.UNKNOWN,p.mapping(),"TIMEOUT","확인 중",NOW);
        clearInvocations(client);when(client.now()).thenReturn(NOW.plusSeconds(59));assertThat(writer.reconcile(1L,p,p.steps().getFirst(),unknown).code()).isEqualTo("WAIT_RECONCILE");verify(client,never()).rawSummary(any());
        when(client.now()).thenReturn(NOW.plusSeconds(60));assertThat(writer.reconcile(1L,p,p.steps().getFirst(),unknown).state()).isEqualTo(State.UNKNOWN);verify(client,never()).write(any(),any(),any(),any());
    }
    @Test void knownCreateMapsReturnedOptionsBySkuAndNameRatherThanArrayOrder(){
        var expected=(ObjectNode)JSON.readTree(BASE);var first=expected.path("items").get(0).deepCopy().asObject();var second=first.deepCopy();second.put("itemName","화이트");second.put("externalVendorSku","SKU-B");expected.path("items").asArray().add(second);
        var actual=expected.deepCopy();actual.put("sellerProductId",555);var reversed=JSON.createArrayNode();var b=second.deepCopy();b.put("sellerProductItemId",333);b.put("vendorItemId",444);reversed.add(b);reversed.add(first);actual.set("items",reversed);when(client.rawProduct("555")).thenReturn(actual);
        var mapping=new Mapping("account",null,List.of(new OptionMapping("opt-A",null,null),new OptionMapping("opt-B",null,null)));var step=new Step("s",Type.CREATE,null,"POST",CoupangProductClient.PATH,"",expected.toString(),"{}",expected.toString());var p=new Prepared("account",mapping,List.of(step),List.of(),NOW,List.of("SKU-A"));
        var r=writer.reconcile(1L,p,step,new Result(State.ACCEPTED,new Mapping("account","555",mapping.options()),"SUCCESS","접수",NOW));
        assertThat(r.state()).isEqualTo(State.CONFIRMED);assertThat(r.mapping().options()).containsExactly(new OptionMapping("opt-A","111","222"),new OptionMapping("opt-B","333","444"));
    }
    @Test void unknownCreateDoesNotLinkDifferentProductWithSameSkuAndName(){
        var p=writer.prepare(1L,fresh(),null,false);var step=p.steps().getFirst();var actual=JSON.readTree(step.expectedJson()).deepCopy().asObject();actual.put("sellerProductId",555);actual.path("items").get(0).asObject().put("sellerProductItemId",111);actual.put("brand","다른 브랜드");
        when(client.now()).thenReturn(NOW.plusSeconds(60));when(client.rawSummary("SKU-A")).thenReturn(JSON.readTree("[{\"sellerProductId\":555}]"));when(client.rawProduct("555")).thenReturn(actual);
        var previous=new Result(State.UNKNOWN,p.mapping(),"TIMEOUT","확인 중",NOW);var unlinked=writer.reconcile(1L,p,step,previous);
        assertThat(unlinked.state()).isEqualTo(State.UNKNOWN);assertThat(unlinked.mapping().sellerProductId()).isNull();assertThat(unlinked.mapping().options().getFirst().sellerProductItemId()).isNull();
        actual.put("brand","브랜드");var linked=writer.reconcile(1L,p,step,previous);assertThat(linked.state()).isEqualTo(State.CONFIRMED);assertThat(linked.mapping().sellerProductId()).isEqualTo("555");verify(client,never()).write(any(),any(),any(),any());
    }
    @Test void urlSafetyAndUnsupportedServiceAreExplicit(){
        assertThat(DefaultCoupangWriteGateway.https("https://images.example.com/a.jpg")).isEqualTo("https://images.example.com/a.jpg");
        for(var bad:List.of("http://images.example.com/a.jpg","https://user@images.example.com/a.jpg","https://127.0.0.1/a.jpg","https://192.168.1.1/a.jpg","https://localhost/a.jpg","/api/marketplaces/assets/a"))assertThatThrownBy(()->DefaultCoupangWriteGateway.https(bad)).isInstanceOf(InputValidationFailure.class);
        var d=fresh();var unsupported=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),List.of(new ServiceOption(UUID.randomUUID().toString(),"포장",List.of("O","X"))),d.media(),d.delivery(),d.selectedMarkets(),d.markets());
        assertThatThrownBy(()->writer.prepare(1L,unsupported,null,false)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("서비스 선택지");
    }
    @Test void generatedVendorItemIdIsRefreshedButDifferentExistingIdIsRejected(){
        var d=option(imported(false),"170","8");var p=writer.prepare(1L,d,null,false);
        assertThat(p.mapping().options().getFirst().vendorItemId()).isEqualTo("222");assertThat(p.steps()).extracting(Step::type).contains(Type.PRICE,Type.STOCK);
        var raw=(ObjectNode)JSON.readTree(BASE);raw.path("items").get(0).asObject().put("vendorItemId",333);when(client.rawProduct("123")).thenReturn(raw);
        assertThatThrownBy(()->writer.prepare(1L,imported(true),null,false)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("옵션 ID");
    }
    @Test void leadingZeroNumbersAreCanonicalJsonNumbers(){
        var p=writer.prepare(1L,option(imported(true),"00170","0008"),null,false);
        assertThat(p.steps().getFirst().path()).endsWith("/prices/170");assertThat(JSON.readTree(p.steps().getFirst().expectedJson()).path("salePrice").asInt()).isEqualTo(170);
    }
    @Test void createIdentityDoesNotClaimUnreflectedShippingOrBrandSucceeded(){
        var p=writer.prepare(1L,fresh(),null,false);var s=p.steps().getFirst();var actual=JSON.readTree(s.expectedJson()).deepCopy().asObject();actual.put("sellerProductId",555);actual.path("items").get(0).asObject().put("sellerProductItemId",111);actual.path("items").get(0).asObject().putNull("vendorItemId");actual.put("brand","다른 브랜드");when(client.rawProduct("555")).thenReturn(actual);
        var previous=new Result(State.ACCEPTED,new Mapping("account","555",p.mapping().options()),"SUCCESS","접수",NOW);
        var unreflected=writer.reconcile(1L,p,s,previous);assertThat(unreflected.state()).isEqualTo(State.ACCEPTED);assertThat(unreflected.mapping().options().getFirst().sellerProductItemId()).isEqualTo("111");
        actual.put("brand","브랜드");assertThat(writer.reconcile(1L,p,s,previous).state()).isEqualTo(State.CONFIRMED);
        actual.put("deliveryCharge",100);assertThat(writer.reconcile(1L,p,s,previous).state()).isEqualTo(State.ACCEPTED);
    }
    @Test void descriptionPrivateAndRelativeImagesAreBlocked(){
        var d=fresh();for(String html:List.of("<img src='/missing.jpg'>","<img src='/api/marketplaces/assets/unselected'>","<img src=https://127.0.0.1/a.jpg>")){
            var changed=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(d.media().images(),List.of(new Content(UUID.randomUUID().toString(),"HTML",html,d.options().getFirst().id()))),d.delivery(),d.selectedMarkets(),d.markets());
            assertThatThrownBy(()->writer.prepare(1L,changed,null,false)).isInstanceOf(InputValidationFailure.class);
        }
    }
    @Test void postTimeoutIsNeverRetriedAndOnlyExplicit429IsRetried()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var mode=new AtomicInteger(0);
        server.createContext("/",ex->{int n=calls.incrementAndGet();try{if(mode.get()==0){ex.sendResponseHeaders(200,0);ex.getResponseBody().write('x');ex.getResponseBody().flush();Thread.sleep(250);}else{ex.getResponseHeaders().set("Retry-After","0");ex.sendResponseHeaders(n==1?429:200,-1);}}catch(Exception ignored){}finally{ex.close();}});server.start();
        try{var transport=new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(100),4096);var c=new CoupangProductClient("A-test","fake-access","fake-secret",JSON,transport,Clock.fixed(NOW,ZoneOffset.UTC));
            assertThatThrownBy(()->c.writeSession(()->c.write("POST",CoupangProductClient.PATH,"","{}"))).isInstanceOf(MarketplaceFailure.class);assertThat(calls.get()).isEqualTo(1);
            Thread.sleep(300);mode.set(1);calls.set(0);var c2=new CoupangProductClient("A-test","fake-access","fake-secret",JSON,transport,Clock.fixed(NOW,ZoneOffset.UTC));assertThat(c2.writeSession(()->c2.write("POST",CoupangProductClient.PATH,"","{}"))).satisfies(r->assertThat(r.status()).isEqualTo(200));assertThat(calls.get()).isEqualTo(2);
        }finally{server.stop(0);}
    }
    Mapping mapping(Document d){var c=d.markets().get(Market.COUPANG).coupang();return new Mapping("account",c.sellerProductId(),c.options().stream().map(o->new OptionMapping(o.optionId(),o.sellerProductItemId(),o.vendorItemId())).toList());}
    MarketplaceEditing.Change change(String path,String option,Object value){return new MarketplaceEditing.Change(path,option,value);}
    @Test void selectedDescriptionNeverReadsInventoryOrRestoresStalePriceStock(){
        var observed=imported(true);var o=observed.options().getFirst();var raw=JSON.readTree(BASE).deepCopy().asObject();raw.path("items").get(0).asObject().put("salePrice",110);raw.path("items").get(0).asObject().put("maximumBuyCount",7);raw.put("brand","외부 변경 브랜드");when(client.rawProduct("123")).thenReturn(raw);
        when(client.rawInventory(anyString())).thenThrow(new MarketplaceFailure(MarketplaceFailure.Kind.NETWORK));
        var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("media.contents",o.id(),List.of(new Content(UUID.randomUUID().toString(),"HTML","새 설명",o.id())))));
        assertThat(p.editIntent()).isNotNull();assertThat(p.steps()).extracting(Step::type).containsExactly(Type.PRODUCT);
        var body=JSON.readTree(p.steps().getFirst().bodyJson());assertThat(body.path("brand").asString()).isEqualTo("외부 변경 브랜드");assertThat(body.path("items").get(0).path("salePrice").asInt()).isEqualTo(110);assertThat(body.path("items").get(0).path("maximumBuyCount").asInt()).isEqualTo(7);assertThat(p.steps().getFirst().bodyJson()).contains("futureItem","futureContent");
        when(client.write(any(),any(),any(),any())).thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\"}".getBytes()));var sent=new ArrayList<Step>();writer.execute(1L,p,p.steps().getFirst(),p.mapping(),sent::add);assertThat(sent).extracting(Step::type).containsExactly(Type.PRODUCT);assertThat(JSON.readTree(sent.getFirst().bodyJson()).path("items").get(0).path("salePrice").asInt()).isEqualTo(110);assertThat(JSON.readTree(sent.getFirst().bodyJson()).path("items").get(0).path("maximumBuyCount").asInt()).isEqualTo(7);
        verify(client,never()).rawInventory(anyString());
        verify(client,never()).rawInventory(anyString(),anyBoolean(),anyBoolean());
    }
    @Test void selectedPriceIsIndependentFromQuantityAndZeroStockIsAnExplicitChange(){
        var observed=imported(true);var id=observed.options().getFirst().id();when(client.rawInventory("222")).thenReturn(JSON.readTree("{\"salePrice\":100,\"amountInStock\":7,\"onSale\":true}"));
        var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("options.price",id,"170")));
        assertThat(p.steps()).extracting(Step::type).containsExactly(Type.PRICE);verify(client,times(1)).rawInventory("222");
        clearInvocations(client);when(client.rawInventory("222")).thenReturn(JSON.readTree("{\"salePrice\":110,\"amountInStock\":9,\"onSale\":true}"));
        var zero=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("options.quantity",id,"0")));
        assertThat(zero.steps()).extracting(Step::type).containsExactly(Type.STOCK);assertThat(zero.steps().getFirst().path()).endsWith("/quantities/0");verify(client,times(1)).rawInventory("222");
    }
    @Test void selectedFieldExternalChangeConflictsButAlreadyDesiredIsNoop(){
        var observed=imported(true);var id=observed.options().getFirst().id();when(client.rawInventory("222")).thenReturn(JSON.readTree("{\"salePrice\":110,\"amountInStock\":9,\"onSale\":true}"));
        assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("options.price",id,"170")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("선택한 항목");
        assertThat(writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("options.price",id,"110"))).steps()).isEmpty();
    }
    @Test void selectedExecutionRecomposesUnselectedRemoteChangesAndRecordsActualRequest(){
        var observed=imported(true);var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("common.name",null,"새 상품명")));
        var raw=JSON.readTree(BASE).deepCopy().asObject();raw.put("brand","나중 브랜드");raw.path("future").asObject().put("late","keep");when(client.rawProduct("123")).thenReturn(raw);when(client.write(any(),any(),any(),any())).thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\"}".getBytes()));
        var sent=new ArrayList<Step>();var result=writer.execute(1L,p,p.steps().getFirst(),p.mapping(),sent::add);
        assertThat(sent).hasSize(1);assertThat(JSON.readTree(sent.getFirst().bodyJson()).path("brand").asString()).isEqualTo("나중 브랜드");assertThat(sent.getFirst().bodyJson()).contains("late");assertThat(result.requestJson()).contains("나중 브랜드","새 상품명");verify(client,never()).rawInventory(anyString());
    }
    @Test void selectedExecutionConflictsOnTouchedFieldAndDispatchHookFailureNeverWrites(){
        var observed=imported(true);var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("common.name",null,"새 상품명")));var raw=JSON.readTree(BASE).deepCopy().asObject();raw.put("sellerProductName","다른 변경");when(client.rawProduct("123")).thenReturn(raw);
        assertThat(writer.execute(1L,p,p.steps().getFirst(),p.mapping()).code()).isEqualTo("BASELINE_CHANGED");verify(client,never()).write(any(),any(),any(),any());
        when(client.rawProduct("123")).thenAnswer(i->JSON.readTree(BASE));assertThatThrownBy(()->writer.execute(1L,p,p.steps().getFirst(),p.mapping(),s->{throw new IllegalStateException("snapshot persistence failure");})).isInstanceOf(IllegalStateException.class);verify(client,never()).write(any(),any(),any(),any());
    }
    @Test void selectedTypedPatchRejectsRawIdsAndReadOnlyPurchaseAttributes(){
        var observed=imported(true);var id=observed.options().getFirst().id();
        assertThatThrownBy(()->CoupangEditPatch.apply(observed,List.of(change("markets.COUPANG.coupang.sellerProductId",null,"999")))).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->CoupangEditPatch.apply(observed,List.of(change("media.contents",id,List.of(Map.of("id",UUID.randomUUID().toString(),"type","HTML","value","본문","optionId",id,"rawPrivate",true)))))).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->CoupangEditPatch.apply(observed,List.of(change("media.images",id,List.of(Map.of("id",UUID.randomUUID().toString(),"url","https://images.example.com/a.jpg","representative","true","order",0,"optionId",id)))))).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("options.price",id,"90071992547409920")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("범위");
        assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.coupang.options.attributes",id,List.of(new CoupangCatalog.Attribute("색상","화이트","EXPOSED")))))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("구매 속성");
    }
    @Test void projectionKeepsStableOptionAndMediaIdsWithoutOverwritingReference(){
        var reference=imported(true);var source=reference.markets().get(Market.COUPANG).coupang().source();var a=CoupangEditingProjection.latest(reference,mapping(reference),source);var b=CoupangEditingProjection.latest(reference,mapping(reference),source);
        assertThat(a.options().getFirst().id()).isEqualTo(reference.options().getFirst().id());assertThat(a.media()).isEqualTo(b.media());assertThat(reference).isNotSameAs(a);
    }
    @Test void selectedWholeSettingsOnlyChangesEditedFieldAndDoesNotRequestApprovalWithoutChanges(){
        var observed=imported(true);var wanted=new ArrayList<>(observed.markets().get(Market.COUPANG).coupang().settings());wanted.replaceAll(f->f.name().equals("saleEndedAt")?new CoupangCatalog.Field(f.name(),"2098-12-31T23:59:59"):f);
        var raw=JSON.readTree(BASE).deepCopy().asObject();raw.put("manufacture","외부 제조사");raw.putNull("productGroup");when(client.rawProduct("123")).thenReturn(raw);
        var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.coupang.settings",null,wanted)));
        assertThat(p.editIntent().changes()).extracting(MarketplaceEditing.Change::path).containsExactly("markets.COUPANG.coupang.settings.saleEndedAt");
        var body=JSON.readTree(p.steps().getFirst().bodyJson());assertThat(body.path("manufacture").asString()).isEqualTo("외부 제조사");assertThat(body.path("productGroup").isNull()).isTrue();assertThat(body.path("saleEndedAt").asString()).isEqualTo("2098-12-31T23:59:59");
        assertThat(writer.prepareSelected(1L,observed,mapping(observed),true,observed,List.of()).steps()).isEmpty();verify(client,never()).rawInventory(anyString());
    }
    @Test void selectedRemoteOptionOrderPreservesWireOrderAndReadbackMatchesById(){
        var seed=imported(true);var original=JSON.readTree(BASE).deepCopy().asObject();var second=original.path("items").get(0).deepCopy().asObject();second.put("sellerProductItemId",333);second.put("vendorItemId",444);second.put("itemName","화이트");second.put("externalVendorSku","SKU-B");original.path("items").asArray().add(second);
        String firstId=seed.options().getFirst().id(),secondId=UUID.randomUUID().toString();var links=new Mapping("account","123",List.of(new OptionMapping(firstId,"111","222"),new OptionMapping(secondId,"333","444")));
        var parser=new CoupangProductClient("A-test","fake-access","fake-secret",JSON);var observed=CoupangEditingProjection.latest(seed,links,parser.editorDocument(original,Map.of(),Map.of()));
        var reversed=original.deepCopy();var rows=JSON.createArrayNode();rows.add(second);rows.add(original.path("items").get(0));reversed.set("items",rows);when(client.rawProduct("123")).thenReturn(reversed);
        assertThat(writer.prepareSelected(1L,observed,links,false,observed,List.of()).steps()).isEmpty();
        var p=writer.prepareSelected(1L,observed,links,false,observed,List.of(change("options.name",firstId,"새 옵션명")));var body=JSON.readTree(p.steps().getFirst().bodyJson());
        assertThat(body.path("items").get(0).path("sellerProductItemId").asInt()).isEqualTo(333);assertThat(body.path("items").get(1).path("itemName").asString()).isEqualTo("새 옵션명");
        when(client.write(any(),any(),any(),any())).thenAnswer(i->{var sent=JSON.readTree((String)i.getArgument(3)).deepCopy().asObject();var freshRows=JSON.createArrayNode();freshRows.add(sent.path("items").get(1));freshRows.add(sent.path("items").get(0));sent.set("items",freshRows);when(client.rawProduct("123")).thenReturn(sent);return new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\"}".getBytes());});
        assertThat(writer.execute(1L,p,p.steps().getFirst(),links).state()).isEqualTo(State.CONFIRMED);verify(client,never()).rawInventory(anyString());
    }
    @Test void selectedSearchAttributePreservesOtherChangedRowsDuringPrepareAndDispatch(){
        var seed=imported(true);var raw=JSON.readTree(BASE).deepCopy().asObject();var attrs=raw.path("items").get(0).path("attributes").asArray();attrs.add(JSON.readTree("{\"attributeTypeName\":\"모델\",\"attributeValueName\":\"A\",\"exposed\":\"NONE\",\"futureAttr\":\"keep\"}"));attrs.add(JSON.readTree("{\"attributeTypeName\":\"검색어\",\"attributeValueName\":\"B\",\"exposed\":\"NONE\"}"));
        var parser=new CoupangProductClient("A-test","fake-access","fake-secret",JSON);var observed=CoupangEditingProjection.latest(seed,mapping(seed),parser.editorDocument(raw,Map.of(),Map.of()));var id=observed.options().getFirst().id();var wanted=observed.markets().get(Market.COUPANG).coupang().options().getFirst().attributes().stream().map(a->a.name().equals("모델")?new CoupangCatalog.Attribute(a.name(),"A2",a.exposed()):a).toList();
        var before=raw.deepCopy();before.path("items").get(0).path("attributes").get(2).asObject().put("attributeValueName","B2");when(client.rawProduct("123")).thenReturn(before);
        var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.coupang.options.attributes",id,wanted)));
        assertThat(p.steps().getFirst().bodyJson()).contains("A2","B2","futureAttr");var dispatch=before.deepCopy();dispatch.path("items").get(0).path("attributes").get(2).asObject().put("attributeValueName","B3");when(client.rawProduct("123")).thenReturn(dispatch);when(client.write(any(),any(),any(),any())).thenReturn(new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\"}".getBytes()));var sent=new ArrayList<Step>();writer.execute(1L,p,p.steps().getFirst(),p.mapping(),sent::add);assertThat(sent.getFirst().bodyJson()).contains("A2","B3","futureAttr");
        dispatch.path("items").get(0).path("attributes").get(1).asObject().put("attributeValueName","A3");assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.coupang.options.attributes",id,wanted)))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("선택한 항목");
    }
    @Test void selectedCreateMarkerAndCommonPatchWorkWithoutOverrides(){
        var d=fresh();var m=d.markets().get(Market.COUPANG);var noOverrides=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(m.categoryCode(),null,m.coupang(),null,null)));
        var p=writer.prepareSelected(1L,noOverrides,null,false,noOverrides,List.of(change("common.name",null,"신규 상품")));
        assertThat(p.editIntent()).isNotNull();assertThat(JSON.readTree(p.steps().getFirst().bodyJson()).path("sellerProductName").asString()).isEqualTo("신규 상품");
    }
    @Test void commonOptionSettingsExpandPerMappedOptionAndDetectSecondOptionConflict(){
        var seed=imported(true);var raw=JSON.readTree(BASE).deepCopy().asObject();raw.path("items").get(0).asObject().put("modelNo","MODEL-A");var second=raw.path("items").get(0).deepCopy().asObject();second.put("sellerProductItemId",333);second.put("vendorItemId",444);second.put("externalVendorSku","SKU-B");second.put("modelNo","MODEL-B");raw.path("items").asArray().add(second);
        var links=new Mapping("account","123",List.of(new OptionMapping(seed.options().getFirst().id(),"111","222"),new OptionMapping(UUID.randomUUID().toString(),"333","444")));var parser=new CoupangProductClient("A-test","fake-access","fake-secret",JSON);var observed=CoupangEditingProjection.latest(seed,links,parser.editorDocument(raw,Map.of(),Map.of()));when(client.rawProduct("123")).thenReturn(raw);
        var p=writer.prepareSelected(1L,observed,links,false,observed,List.of(change("common.model",null,"NEW-MODEL")));assertThat(p.editIntent().changes()).hasSize(2).allSatisfy(c->assertThat(c.path()).isEqualTo("markets.COUPANG.coupang.options.registration.modelNo"));
        assertThat(JSON.readTree(p.steps().getFirst().bodyJson()).path("items")).allSatisfy(row->assertThat(row.path("modelNo").asString()).isEqualTo("NEW-MODEL"));
        raw.path("items").get(1).asObject().put("modelNo","EXTERNAL-MODEL");assertThatThrownBy(()->writer.prepareSelected(1L,observed,links,false,observed,List.of(change("common.model",null,"NEW-MODEL")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("선택한 항목");
        assertThatThrownBy(()->writer.prepareSelected(1L,observed,links,false,observed,List.of(change("markets.COUPANG.overrides.description",null,"새 설명")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("옵션별");verify(client,never()).rawInventory(anyString());
    }
    @Test void selectedBrandOverrideUsesActualBrandForConflictAndKeepsCreateOverridePolicy(){
        var observed=imported(true);assertThat(observed.markets().get(Market.COUPANG).overrides().brand()).isNull();var remote=JSON.readTree(BASE).deepCopy().asObject();remote.put("brand","외부 변경 브랜드");when(client.rawProduct("123")).thenReturn(remote);
        assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.overrides.brand",null,"새 브랜드")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("선택한 항목");
        var noop=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.overrides.brand",null,"외부 변경 브랜드")));assertThat(noop.steps()).isEmpty();assertThat(noop.editIntent().changes()).extracting(MarketplaceEditing.Change::path).containsExactly("common.brand");
        var newProduct=fresh();var create=writer.prepareSelected(1L,newProduct,null,false,newProduct,List.of(change("markets.COUPANG.overrides.brand",null,"등록 브랜드")));assertThat(JSON.readTree(create.steps().getFirst().bodyJson()).path("brand").asString()).isEqualTo("등록 브랜드");verify(client,never()).rawInventory(anyString());
    }
    @Test void selectedNameAliasRejectsDuplicatePhysicalFieldAndPreviewMatchesWire(){
        var observed=imported(true);assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("common.name",null,"공통 상품명"),change("markets.COUPANG.overrides.name",null,"별도 상품명")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("중복");
        var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.overrides.name",null,"새 상품명")));assertThat(p.editIntent().changes()).extracting(MarketplaceEditing.Change::path).containsExactly("common.name");assertThat(p.changes()).hasSize(1).allSatisfy(c->assertThat(c.after()).isEqualTo("새 상품명"));assertThat(JSON.readTree(p.steps().getFirst().bodyJson()).path("sellerProductName").asString()).isEqualTo("새 상품명");verify(client,never()).rawInventory(anyString());
    }
    @Test void manufacturerDeliveryAndSkuAliasesHaveOneIntentAndWireValue(){
        var observed=imported(true);var id=observed.options().getFirst().id();var aliases=List.of(
            change("markets.COUPANG.coupang.settings.manufacture",null,"새 제조사"),
            change("markets.COUPANG.coupang.delivery.returnCharge",null,"4000"),
            change("markets.COUPANG.coupang.options.registration.externalVendorSku",id,"NEW-SKU"));
        var paths=List.of("common.manufacturer","delivery.returnCharge","options.sku");
        for(int n=0;n<aliases.size();n++){
            var alias=aliases.get(n);var canonical=change(paths.get(n),alias.optionId(),alias.value());assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(alias,canonical))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("중복");
        }
        var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,aliases);assertThat(p.editIntent().changes()).extracting(MarketplaceEditing.Change::path).containsExactlyElementsOf(paths);
        var product=JSON.readTree(p.steps().stream().filter(s->s.type()==Type.PRODUCT).findFirst().orElseThrow().bodyJson());assertThat(product.path("manufacture").asString()).isEqualTo("새 제조사");assertThat(product.path("items").get(0).path("externalVendorSku").asString()).isEqualTo("NEW-SKU");var delivery=JSON.readTree(p.steps().stream().filter(s->s.type()==Type.DELIVERY).findFirst().orElseThrow().bodyJson());assertThat(delivery.path("returnCharge").asInt()).isEqualTo(4000);verify(client,never()).rawInventory(anyString());
        var wholeDelivery=new ArrayList<>(observed.markets().get(Market.COUPANG).coupang().delivery());wholeDelivery.replaceAll(f->f.name().equals("returnCharge")?new CoupangCatalog.Field(f.name(),"4000"):f);assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("markets.COUPANG.coupang.delivery",null,wholeDelivery),change("delivery.returnCharge",null,"5000")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("중복");
    }
    @Test void pendingCurrentPriceAndQuantityCannotBeChangedButDescriptionCan(){
        var observed=imported(false);var id=observed.options().getFirst().id();assertThat(observed.options().getFirst().price()).isEmpty();assertThat(observed.options().getFirst().quantity()).isEmpty();var raw=JSON.readTree(BASE).deepCopy().asObject();raw.path("items").get(0).asObject().putNull("vendorItemId");when(client.rawProduct("123")).thenReturn(raw);
        for(var path:List.of("options.price","options.quantity"))assertThatThrownBy(()->writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change(path,id,path.endsWith("price")?"170":"0")))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("현재 가격·재고");
        var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change("media.contents",id,List.of(new Content(UUID.randomUUID().toString(),"HTML","새 설명",id)))));assertThat(p.steps()).extracting(Step::type).containsExactly(Type.PRODUCT);var body=JSON.readTree(p.steps().getFirst().bodyJson());assertThat(body.path("items").get(0).path("salePrice").asInt()).isEqualTo(100);assertThat(body.path("items").get(0).path("maximumBuyCount").asInt()).isEqualTo(9);verify(client,never()).rawInventory(anyString());
    }
    @Test void selectedPriceOrStockWorksWhenOnlyThatCurrentFieldIsKnown(){
        var seed=imported(true);var parser=new CoupangProductClient("A-test","fake-access","fake-secret",JSON);
        for(boolean price:List.of(true,false)){
            var partial=JSON.readTree(price?"{\"sellerItemId\":222,\"salePrice\":100,\"onSale\":true}":"{\"sellerItemId\":222,\"amountInStock\":9,\"onSale\":true}");var envelope=JSON.createObjectNode();envelope.put("code","SUCCESS");envelope.set("data",partial);assertThat(parser.selectedInventory(JSON.writeValueAsBytes(envelope),price,!price)).isEqualTo(partial);
            var nullable=envelope.deepCopy();nullable.path("data").asObject().putNull(price?"amountInStock":"salePrice");assertThat(parser.selectedInventory(JSON.writeValueAsBytes(nullable),price,!price).path(price?"amountInStock":"salePrice").isNull()).isTrue();assertThatThrownBy(()->parser.selectedInventory(JSON.writeValueAsBytes(envelope),!price,price)).isInstanceOf(MarketplaceFailure.class);
            var bad=envelope.deepCopy();bad.path("data").asObject().remove("onSale");assertThatThrownBy(()->parser.selectedInventory(JSON.writeValueAsBytes(bad),price,!price)).isInstanceOf(MarketplaceFailure.class);var invalidOther=envelope.deepCopy();invalidOther.path("data").asObject().put(price?"amountInStock":"salePrice",-1);assertThatThrownBy(()->parser.selectedInventory(JSON.writeValueAsBytes(invalidOther),price,!price)).isInstanceOf(MarketplaceFailure.class);
            var observed=CoupangEditingProjection.latest(seed,mapping(seed),parser.editorDocument(JSON.readTree(BASE),Map.of("222",new CoupangCatalog.CurrentInventory("222",price?100L:null,price?null:9L,true)),Map.of()));var id=observed.options().getFirst().id();var current=new java.util.concurrent.atomic.AtomicReference<JsonNode>(partial);doAnswer(i->current.get()).when(client).rawInventory("222");
            var p=writer.prepareSelected(1L,observed,mapping(observed),false,observed,List.of(change(price?"options.price":"options.quantity",id,price?"170":"8")));assertThat(p.steps()).extracting(Step::type).containsExactly(price?Type.PRICE:Type.STOCK);assertThat(p.editIntent().observed().options().getFirst().price()).isEqualTo(price?"100":"");assertThat(p.editIntent().observed().options().getFirst().quantity()).isEqualTo(price?"":"9");assertThat(JSON.readTree(p.steps().getFirst().baselineJson()).size()).isEqualTo(1);
            doAnswer(i->{var reflected=partial.deepCopy().asObject();reflected.put(price?"salePrice":"amountInStock",price?170:8);current.set(reflected);return new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\"}".getBytes());}).when(client).write(any(),any(),any(),any());assertThat(writer.execute(1L,p,p.steps().getFirst(),p.mapping()).state()).isEqualTo(State.CONFIRMED);
        }
    }
    @Test void selectedSearchTagsStayPerOptionAndProtectChangedSelectedTags(){
        var seed=imported(true);var raw=JSON.readTree(BASE).deepCopy().asObject();raw.path("items").get(0).asObject().set("searchTags",JSON.readTree("[\"블랙\",\"장지갑\"]"));var second=raw.path("items").get(0).deepCopy().asObject();second.put("sellerProductItemId",333);second.put("vendorItemId",444);second.put("externalVendorSku","SKU-B");second.set("searchTags",JSON.readTree("[\"화이트\",\"선물\"]"));raw.path("items").asArray().add(second);
        String firstId=seed.options().getFirst().id();var links=new Mapping("account","123",List.of(new OptionMapping(firstId,"111","222"),new OptionMapping(UUID.randomUUID().toString(),"333","444")));var parser=new CoupangProductClient("A-test","fake-access","fake-secret",JSON);var source=parser.editorDocument(raw,Map.of("222",new CoupangCatalog.CurrentInventory("222",100L,9L,true)),Map.of());var observed=CoupangEditingProjection.latest(seed,links,source);
        assertThat(source.settings()).noneMatch(f->f.name().equals("searchTags"));assertThat(source.delivery()).noneMatch(f->f.name().equals("searchTags"));assertThat(observed.markets().get(Market.COUPANG).coupang().options()).allSatisfy(o->assertThat(o.registration().stream().filter(f->f.name().equals("searchTags"))).hasSize(1));
        var latest=raw.deepCopy();latest.path("items").get(1).asObject().set("searchTags",JSON.readTree("[\"외부 변경 화이트\"]"));when(client.rawProduct("123")).thenReturn(latest);var wanted=change("markets.COUPANG.coupang.options.registration.searchTags",firstId,"가방,파우치");var p=writer.prepareSelected(1L,observed,links,false,observed,List.of(wanted));assertThat(p.steps()).extracting(Step::type).containsExactly(Type.PRODUCT);
        var body=JSON.readTree(p.steps().getFirst().bodyJson());assertThat(body.path("items").get(0).path("searchTags")).isEqualTo(JSON.readTree("[\"가방\",\"파우치\"]"));assertThat(body.path("items").get(1).path("searchTags")).isEqualTo(JSON.readTree("[\"외부 변경 화이트\"]"));
        latest.path("items").get(0).asObject().set("searchTags",JSON.readTree("[\"가방\",\"파우치\"]"));assertThat(writer.prepareSelected(1L,observed,links,false,observed,List.of(wanted)).steps()).isEmpty();verify(client,never()).rawInventory(anyString());verify(client,never()).rawInventory(anyString(),anyBoolean(),anyBoolean());
        var next=writer.prepareSelected(1L,observed,links,false,observed,List.of(wanted,change("options.price",firstId,"170")));assertThat(next.steps()).extracting(Step::type).containsExactly(Type.PRICE);assertThat(next.changes()).hasSize(1);verify(client,times(1)).rawInventory("222",true,false);
        doAnswer(i->{doReturn(JSON.readTree("{\"salePrice\":170,\"amountInStock\":9,\"onSale\":true}")).when(client).rawInventory("222");return new CoupangHttpTransport.Response(200,"{\"code\":\"SUCCESS\"}".getBytes());}).when(client).write(any(),any(),any(),any());var sent=new ArrayList<Step>();assertThat(writer.execute(1L,next,next.steps().getFirst(),next.mapping(),sent::add).state()).isEqualTo(State.CONFIRMED);assertThat(sent).extracting(Step::type).containsExactly(Type.PRICE);assertThat(sent.getFirst().path()).endsWith("/prices/170");
        latest.path("items").get(0).asObject().set("searchTags",JSON.readTree("[\"외부 변경 블랙\"]"));assertThatThrownBy(()->writer.prepareSelected(1L,observed,links,false,observed,List.of(wanted))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("선택한 항목");
    }
}
