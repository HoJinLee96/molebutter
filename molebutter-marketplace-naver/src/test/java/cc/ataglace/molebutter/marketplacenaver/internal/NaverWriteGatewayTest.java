package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverWriteGatewayTest {
    final ObjectMapper json=new ObjectMapper();final NaverGateway gateway=mock(NaverGateway.class);final ImageAssets assets=mock(ImageAssets.class);
    final DefaultNaverWriteGateway writer=new DefaultNaverWriteGateway(mock(BusinessAccess.class),gateway,assets,json);
    final ObjectNode base=json.readTree("""
    {"originProduct":{"originProductNo":123,"statusType":"SALE","saleType":"NEW","leafCategoryId":"50000001","name":"스마트스토어 상품","salePrice":10000,"stockQuantity":5,"detailContent":"<p>SmartEditor 원문</p>","images":{"representativeImage":{"url":"https://shop-phinf.pstatic.net/original.jpg"},"optionalImages":[]},"futureField":{"keep":true},"detailAttribute":{"afterServiceInfo":{"afterServiceTelephoneNumber":"000-000-0000","afterServiceGuideContent":"A/S 안내"},"originAreaInfo":{"originAreaCode":"00"},"minorPurchasable":true,"sellerCodeInfo":{"sellerManagementCode":"SKU"},"productInfoProvidedNotice":{"productInfoProvidedNoticeType":"BAG","bag":{"returnCostReason":"1","noRefundReason":"1","qualityAssuranceStandard":"1","compensationProcedure":"1","troubleShootingContents":"1","type":"가방","material":"가죽","color":"검정","size":"상품상세 참조","manufacturer":"제조사","caution":"상품상세 참조","warrantyPolicy":"상품상세 참조","afterServiceDirector":"상품상세 참조"}}}},"smartstoreChannelProduct":{"channelProductNo":456,"naverShoppingRegistration":false,"channelProductDisplayStatusType":"ON","futureChannel":{"keep":true}}}
    """).asObject();
    NaverWriteGatewayTest(){when(gateway.accountKey()).thenReturn("naver-account");when(gateway.session(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());when(gateway.product("123")).thenAnswer(i->base.deepCopy());when(gateway.search(any())).thenReturn(json.readTree("{\"contents\":[],\"totalPages\":0,\"last\":true}"));when(gateway.uploadImage(anyLong(),any())).thenReturn("https://shop-phinf.pstatic.net/uploaded.jpg");when(gateway.parse(any())).thenAnswer(i->json.readTree(((NaverGateway.Response)i.getArgument(0)).body()));}
    NaverEditor.Input input(){return NaverEditPatch.editor(base).input();}
    MarketplaceDrafts.Document document(){return NaverDraftAdapter.document("1",0L,input());}
    Mapping mapping(){return new Mapping("naver-account","123",List.of(),"456");}
    NaverEditor.Input field(NaverEditor.Input input,String name,Object value){var fields=new LinkedHashMap<>(input.fields());fields.put(name,value);return new NaverEditor.Input(fields,input.optionMode(),input.optionNames(),input.options(),input.images(),input.description());}
    Prepared edit(NaverEditor.Input desired){return writer.prepareSelected(1L,document(),mapping(),false,document(),NaverEditPatch.diff(input(),desired));}
    NaverGateway.Response response(int status,String body){return new NaverGateway.Response(status,body.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,Map.of());}
    @ParameterizedTest @ValueSource(longs={0,5})
    void statusReadbackUsesFinalStockAndDoesNotRepeatAConfirmedPut(long stock){
        base.path("originProduct").asObject().put("statusType","SUSPENSION").put("stockQuantity",stock);
        var prepared=edit(field(input(),"originProduct.statusType","SALE"));var step=prepared.steps().getFirst();
        String expected=stock==0?"OUTOFSTOCK":"SALE";
        assertThat(json.readTree(step.bodyJson()).path("originProduct").path("statusType").asString()).isEqualTo("SALE");
        assertThat(json.readTree(step.expectedJson()).path(NaverEditPatch.PREFIX+"fields.originProduct.statusType").asString()).isEqualTo(expected);
        when(gateway.write(any(),any(),any())).thenAnswer(i->{base.path("originProduct").asObject().put("statusType",expected);return response(200,"{\"originProductNo\":123}");});
        var result=writer.execute(1L,prepared,step,mapping());assertThat(result.state()).isEqualTo(State.CONFIRMED);
        assertThat(writer.reconcile(1L,prepared,step,result).state()).isEqualTo(State.CONFIRMED);
        assertThat(writer.execute(1L,prepared,step,mapping()).state()).isEqualTo(State.CONFIRMED);
        verify(gateway,times(1)).write(any(),any(),any());
    }
    @Test void oldZeroStockSnapshotCanBeConfirmedButAnUnreflectedStatusStaysPending(){
        base.path("originProduct").asObject().put("statusType","SUSPENSION").put("stockQuantity",0);
        var prepared=edit(field(input(),"originProduct.statusType","SALE"));var step=prepared.steps().getFirst();
        var oldExpected=json.readTree(step.expectedJson()).asObject().put(NaverEditPatch.PREFIX+"fields.originProduct.statusType","SALE");
        var legacy=new Step(step.id(),step.type(),step.optionId(),step.method(),step.path(),step.query(),step.bodyJson(),step.baselineJson(),json.writeValueAsString(oldExpected));
        var accepted=new Result(State.ACCEPTED,mapping(),"ACCEPTED",null,Instant.now(),step.bodyJson());
        assertThat(writer.reconcile(1L,prepared,legacy,accepted).state()).isEqualTo(State.ACCEPTED);
        base.path("originProduct").asObject().put("statusType","OUTOFSTOCK");
        assertThat(writer.reconcile(1L,prepared,legacy,accepted).state()).isEqualTo(State.CONFIRMED);
        verify(gateway,never()).write(any(),any(),any());
    }
    @Test void expectedStatusIsRecomputedFromTheActualRequestAfterStockChanges(){
        base.path("originProduct").asObject().put("statusType","SUSPENSION").put("stockQuantity",0);
        var prepared=edit(field(input(),"originProduct.statusType","SALE"));var step=prepared.steps().getFirst();
        base.path("originProduct").asObject().put("stockQuantity",5);
        var sent=new java.util.concurrent.atomic.AtomicReference<Step>();
        when(gateway.write(any(),any(),any())).thenAnswer(i->{base.path("originProduct").asObject().put("statusType","SALE");return response(200,"{\"originProductNo\":123}");});
        var result=writer.execute(1L,prepared,step,mapping(),sent::set);assertThat(result.state()).isEqualTo(State.CONFIRMED);
        var persisted=json.readValue(json.writeValueAsString(sent.get()),Step.class);
        assertThat(json.readTree(persisted.expectedJson()).path(NaverEditPatch.PREFIX+"fields.originProduct.statusType").asString()).isEqualTo("SALE");
        assertThat(writer.reconcile(1L,prepared,persisted,result).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void explicitSuspensionAlsoUsesOutOfStockReadbackWhenZeroStockIsSent(){
        var prepared=edit(field(field(input(),"originProduct.statusType","SUSPENSION"),"originProduct.stockQuantity",0L));var step=prepared.steps().getFirst();
        assertThat(json.readTree(step.bodyJson()).path("originProduct").path("statusType").asString()).isEqualTo("SUSPENSION");
        when(gateway.write(any(),any(),any())).thenAnswer(i->{base.path("originProduct").asObject().put("statusType","OUTOFSTOCK").put("stockQuantity",0);return response(200,"{\"originProductNo\":123}");});
        assertThat(writer.execute(1L,prepared,step,mapping()).state()).isEqualTo(State.CONFIRMED);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingChannelNumberUsesOriginPutForChannelOnlyAndCombinedChanges(boolean combined){
        base.path("originProduct").asObject().remove("originProductNo");base.put("originProductNo",123);
        base.path("smartstoreChannelProduct").asObject().remove("channelProductNo");
        var before=input();var desired=field(before,"smartstoreChannelProduct.channelProductDisplayStatusType","SUSPENSION");
        if(combined)desired=field(desired,"originProduct.name","원상품명 변경");
        var d=document();var mapping=new Mapping("naver-account","123",List.of(),null);
        var prepared=writer.prepareSelected(1L,d,mapping,false,d,NaverEditPatch.diff(before,desired));
        assertThat(prepared.steps()).hasSize(1);var step=prepared.steps().getFirst();assertThat(step.path()).isEqualTo("/v2/products/origin-products/123");
        var body=json.readTree(step.bodyJson());assertThat(body.path("originProduct").has("detailContent")).isFalse();assertThat(body.path("smartstoreChannelProduct").has("channelProductNo")).isFalse();
        when(gateway.write(any(),any(),any())).thenAnswer(i->{var sent=(JsonNode)i.getArgument(2);base.path("smartstoreChannelProduct").asObject().put("channelProductDisplayStatusType",sent.path("smartstoreChannelProduct").path("channelProductDisplayStatusType").asString());base.path("originProduct").asObject().put("name",sent.path("originProduct").path("name").asString());return response(200,combined?"{\"originProductNo\":123,\"smartstoreChannelProductNo\":456}":"{\"originProductNo\":123}");});
        var result=writer.execute(1L,prepared,step,mapping);assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.mapping().channelProductId()).isEqualTo(combined?"456":null);
        assertThat(base.path("smartstoreChannelProduct").path("futureChannel").path("keep").asBoolean()).isTrue();
        assertThat(base.path("originProduct").path("detailContent").asString()).isEqualTo("<p>SmartEditor 원문</p>");
        assertThat(writer.reconcile(1L,prepared,step,result).state()).isEqualTo(State.CONFIRMED);verify(gateway,times(1)).write(eq("PUT"),eq("/v2/products/origin-products/123"),any());
    }
    @Test void combinedOriginPutStillRejectsExternalChannelConflicts(){
        base.path("smartstoreChannelProduct").asObject().remove("channelProductNo");var d=document();var mapping=new Mapping("naver-account","123",List.of(),null);
        var prepared=writer.prepareSelected(1L,d,mapping,false,d,NaverEditPatch.diff(input(),field(input(),"smartstoreChannelProduct.channelProductName","입력한 채널명")));
        base.path("smartstoreChannelProduct").asObject().put("channelProductName","외부에서 바꾼 채널명");
        var result=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping);assertThat(result.state()).isEqualTo(State.FAILED);assertThat(result.code()).isEqualTo("BASELINE_CHANGED");verify(gateway,never()).write(any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"missing","null","blank"})
    void firstReleaseDateCanBeEnteredWhenNoDateIsStored(String previous){
        var detail=base.path("originProduct").path("detailAttribute").asObject();
        if(previous.equals("null"))detail.putNull("releaseDate");else if(previous.equals("blank"))detail.put("releaseDate","");
        var prepared=edit(field(input(),"originProduct.detailAttribute.releaseDate","2026-10-10"));var step=prepared.steps().getFirst();
        when(gateway.write(any(),any(),any())).thenAnswer(i->{detail.put("releaseDate",((JsonNode)i.getArgument(2)).path("originProduct").path("detailAttribute").path("releaseDate").asString());return response(200,"{\"originProductNo\":123}");});
        assertThat(writer.execute(1L,prepared,step,mapping()).state()).isEqualTo(State.CONFIRMED);
        assertThatThrownBy(()->edit(field(input(),"originProduct.detailAttribute.releaseDate","2026-10-11"))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("이미 등록된 출시일");
        assertThatThrownBy(()->edit(field(input(),"originProduct.detailAttribute.releaseDate",null))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("이미 등록된 출시일");
        assertThat(edit(input()).steps()).isEmpty();verify(gateway,times(1)).write(any(),any(),any());
    }
    @Test void updatePreservesUneditedFieldsAndOmitsUnchangedSmartEditorContent(){
        var desired=field(input(),"originProduct.salePrice","0");var prepared=edit(desired);assertThat(prepared.steps()).hasSize(1);var step=prepared.steps().getFirst();var body=json.readTree(step.bodyJson());
        assertThat(body.path("originProduct").path("salePrice").isIntegralNumber()).isTrue();assertThat(body.path("originProduct").path("salePrice").asLong()).isZero();assertThat(body.path("originProduct").has("detailContent")).isFalse();assertThat(body.path("originProduct").path("futureField").path("keep").asBoolean()).isTrue();assertThat(body.path("originProduct").has("originProductNo")).isFalse();assertThat(body.path("smartstoreChannelProduct").path("futureChannel").path("keep").asBoolean()).isTrue();assertThat(body.has("requested")).isFalse();
    }
    @Test void soldOutProductNameCanBeUpdatedWithoutRestockingOrForcingApprovalStateToSale(){
        base.path("originProduct").asObject().put("statusType","OUTOFSTOCK").put("stockQuantity",0);
        var prepared=edit(field(input(),"originProduct.name","품절 상품명 수정"));var step=prepared.steps().getFirst();var body=json.readTree(step.bodyJson());
        assertThat(body.path("originProduct").path("statusType").asString()).isEqualTo("SALE");assertThat(body.path("originProduct").path("stockQuantity").asLong()).isZero();
        when(gateway.write(any(),any(),any())).thenAnswer(i->{var sent=(JsonNode)i.getArgument(2);assertThat(sent.path("originProduct").path("statusType").asString()).isEqualTo("SALE");assertThat(sent.path("originProduct").path("stockQuantity").asLong()).isZero();base.path("originProduct").asObject().put("name",sent.path("originProduct").path("name").asString());return response(200,"{\"originProductNo\":123,\"smartstoreChannelProductNo\":456}");});
        assertThat(writer.execute(1L,prepared,step,mapping()).state()).isEqualTo(State.CONFIRMED);assertThat(base.path("originProduct").path("statusType").asString()).isEqualTo("OUTOFSTOCK");verify(gateway,times(1)).write(any(),any(),any());
        base.path("originProduct").asObject().put("statusType","UNADMISSION");assertThat(edit(input()).steps()).isEmpty();assertThatThrownBy(()->edit(field(input(),"originProduct.name","검수 대기 상품명 수정"))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("UNADMISSION").hasMessageContaining("강제 변경하지 않습니다");verify(gateway,times(1)).write(any(),any(),any());
    }
    @Test void persistedRequestJsonAndHttpReadbackConfirmNestedNumericValuesWithoutAnotherPut(){
        var desired=field(field(field(input(),"originProduct.salePrice",2000L),"originProduct.stockQuantity",0L),"originProduct.detailAttribute.productAttributes",List.of(Map.of("attributeSeq",100L,"attributeValueSeq",200L)));
        var prepared=json.readValue(json.writeValueAsString(edit(desired)),Prepared.class);var step=prepared.steps().getFirst();var requestJson=new java.util.concurrent.atomic.AtomicReference<String>();
        when(gateway.write(any(),any(),any())).thenAnswer(i->{var remote=json.readTree(json.writeValueAsString(i.getArgument(2))).asObject();remote.path("originProduct").asObject().put("originProductNo",123).put("detailContent",base.path("originProduct").path("detailContent").asString());remote.path("smartstoreChannelProduct").asObject().put("channelProductNo",456);base.removeAll();for(var e:remote.properties())base.set(e.getKey(),e.getValue());return response(200,"{\"originProductNo\":123,\"smartstoreChannelProductNo\":456}");});
        var result=writer.execute(1L,prepared,step,mapping(),sent->requestJson.set(json.writeValueAsString(sent)));assertThat(result.state()).isEqualTo(State.CONFIRMED);
        var storedStep=json.readValue(requestJson.get(),Step.class);var wire=json.readTree(storedStep.bodyJson());assertThat(wire.path("originProduct").path("salePrice").asLong()).isEqualTo(2000);assertThat(wire.path("originProduct").path("stockQuantity").asLong()).isZero();assertThat(wire.path("originProduct").has("detailContent")).isFalse();assertThat(wire.path("originProduct").path("futureField").path("keep").asBoolean()).isTrue();
        assertThat(writer.reconcile(1L,prepared,storedStep,result).state()).isEqualTo(State.CONFIRMED);assertThat(writer.execute(1L,prepared,storedStep,result.mapping()).state()).isEqualTo(State.CONFIRMED);verify(gateway,times(1)).write(any(),any(),any());
    }
    @Test void createUsesOnePostAndNativeImageApiWithNoForeignIdentifiers(){
        var prepared=writer.prepare(1L,document(),null,true);assertThat(prepared.steps()).extracting(Step::type).containsExactly(Type.CREATE);var body=json.readTree(prepared.steps().getFirst().bodyJson());
        assertThat(body.path("originProduct").path("images").path("representativeImage").path("url").asString()).endsWith("uploaded.jpg");assertThat(body.path("originProduct").has("originProductNo")).isFalse();assertThat(body.path("smartstoreChannelProduct").has("channelProductNo")).isFalse();assertThat(body.has("requested")).isFalse();assertThat(body.path("originProduct").path("detailContent").asString()).contains("SmartEditor");verify(gateway,never()).write(any(),any(),any());
    }
    @Test void pathIdentityProjectionKeepsExistingMappingsWithoutSendingIdentifiersInPut(){
        base.path("originProduct").asObject().remove("originProductNo");base.path("smartstoreChannelProduct").asObject().remove("channelProductNo");
        base.put("originProductNo",123); // The gateway's trusted path identity, outside the documented GET objects.
        var prepared=edit(field(input(),"originProduct.name","수정한 상품명"));var step=prepared.steps().getFirst();
        var body=json.readTree(step.bodyJson());assertThat(body.has("originProductNo")).isFalse();assertThat(body.path("originProduct").has("originProductNo")).isFalse();assertThat(body.path("smartstoreChannelProduct").has("channelProductNo")).isFalse();
        when(gateway.write(any(),any(),any())).thenAnswer(i->{base.path("originProduct").asObject().put("name",((JsonNode)i.getArgument(2)).path("originProduct").path("name").asString());return response(200,"{\"originProductNo\":123,\"smartstoreChannelProductNo\":456}");});
        var result=writer.execute(1L,prepared,step,mapping());assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.mapping().sellerProductId()).isEqualTo("123");assertThat(result.mapping().channelProductId()).isEqualTo("456");
        assertThat(writer.reconcile(1L,prepared,step,result).state()).isEqualTo(State.CONFIRMED);verify(gateway,times(1)).write(any(),any(),any());
    }
    @Test void combinationIdsFollowUuidAndPriceIsAdditionalAmount(){
        var rows=json.createArrayNode().add(json.createObjectNode().put("id",7).put("optionName1","검정").put("price",0).put("stockQuantity",5).put("sellerManagerCode","BLACK").put("usable",true));var info=json.createObjectNode().set("optionCombinations",rows);info.asObject().set("optionCombinationGroupNames",json.createObjectNode().put("optionGroupName1","색상"));base.path("originProduct").path("detailAttribute").asObject().set("optionInfo",info);
        var before=input();var old=before.options().getFirst();var option=new NaverEditor.Option(old.id(),old.values(),-500L,0L,old.sellerManagerCode(),true);var desired=new NaverEditor.Input(before.fields(),"COMBINATION",before.optionNames(),List.of(option),before.images(),before.description());
        var d=NaverDraftAdapter.document("1",0L,before);var m=new Mapping("naver-account","123",List.of(new OptionMapping(old.id(),"7",null)),"456");var prepared=writer.prepareSelected(1L,d,m,false,d,NaverEditPatch.diff(before,desired));var row=json.readTree(prepared.steps().getFirst().bodyJson()).path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations").get(0);
        assertThat(row.path("id").asLong()).isEqualTo(7);assertThat(row.path("price").asLong()).isEqualTo(-500);assertThat(row.path("stockQuantity").asLong()).isZero();assertThat(json.readTree(prepared.steps().getFirst().bodyJson()).path("originProduct").path("salePrice").asLong()).isEqualTo(10000);
        var duplicated=new NaverEditor.Option(UUID.randomUUID().toString(),old.values(),0L,1L,"ANOTHER",true);assertThatThrownBy(()->writer.prepare(1L,NaverDraftAdapter.document("1",0L,new NaverEditor.Input(before.fields(),"COMBINATION",before.optionNames(),List.of(option,duplicated),before.images(),before.description())),null,false)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("중복");
    }
    @Test void externalConflictIsBlockedBeforeAnyWriteAndGroupCannotBeEdited(){
        var desired=field(input(),"originProduct.salePrice",20000L);var prepared=edit(desired);base.path("originProduct").asObject().put("salePrice",15000);var result=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping());assertThat(result.state()).isEqualTo(State.FAILED);assertThat(result.code()).isEqualTo("BASELINE_CHANGED");verify(gateway,never()).write(any(),any(),any());
        base.set("groupProduct",json.createObjectNode().put("groupProductNo",99));assertThatThrownBy(()->edit(desired)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("그룹");
    }
    @Test void uploadedImageReadbackAndFollowingChannelStepUseResolvedUrlsWithoutRepeatingUpload(){
        var before=input();String asset=UUID.randomUUID().toString();var replacement=new NaverEditor.Image(UUID.randomUUID().toString(),asset,"/api/marketplaces/assets/"+asset,true,0);var desired=new NaverEditor.Input(field(before,"smartstoreChannelProduct.channelProductDisplayStatusType","SUSPENSION").fields(),before.optionMode(),before.optionNames(),before.options(),List.of(replacement),"<p>변경된 본문</p>");var prepared=edit(desired);assertThat(prepared.steps()).hasSize(2);assertThat(prepared.steps().getFirst().path()).contains("origin-products");assertThat(prepared.steps().getLast().path()).contains("channel-products");
        when(gateway.write(any(),any(),any())).thenAnswer(i->{var body=((JsonNode)i.getArgument(2)).deepCopy().asObject();var origin=body.path("originProduct").asObject();origin.put("originProductNo",123);if(!origin.has("detailContent"))origin.put("detailContent",base.path("originProduct").path("detailContent").asString());body.path("smartstoreChannelProduct").asObject().put("channelProductNo",456);base.removeAll();for(var e:body.properties())base.set(e.getKey(),e.getValue());return response(200,"{\"originProductNo\":123,\"smartstoreChannelProductNo\":456}");});
        var first=writer.execute(1L,prepared,prepared.steps().getFirst(),mapping());assertThat(first.state()).isEqualTo(State.CONFIRMED);var second=writer.execute(1L,prepared,prepared.steps().getLast(),first.mapping());assertThat(second.state()).isEqualTo(State.CONFIRMED);assertThat(base.path("originProduct").path("detailContent").asString()).isEqualTo("<p>변경된 본문</p>");assertThat(base.path("originProduct").path("images").path("representativeImage").path("url").asString()).endsWith("uploaded.jpg");verify(gateway,times(1)).uploadImage(anyLong(),any());
    }
    @Test void ambiguousCreateNeverMatchesAnIdenticalAlreadyExistingProduct(){
        when(gateway.search(any())).thenReturn(json.readTree("{\"contents\":[{\"originProductNo\":123}],\"totalPages\":1,\"last\":true}"));var prepared=writer.prepare(1L,document(),null,false);var step=prepared.steps().getFirst();var uncertain=new Result(State.UNKNOWN,null,"NETWORK",null,Instant.now());assertThat(writer.reconcile(1L,prepared,step,uncertain).state()).isEqualTo(State.UNKNOWN);verify(gateway,never()).write(any(),any(),any());
    }
    @Test void createIssuedIdIsRecheckedAndBlocksAnotherCreateEvenWhileApprovalIsPending(){
        var prepared=writer.prepare(1L,document(),null,false);var step=prepared.steps().getFirst();
        when(gateway.write(any(),any(),any())).thenAnswer(i->{var body=((JsonNode)i.getArgument(2)).deepCopy().asObject();body.path("originProduct").asObject().put("originProductNo",123).put("statusType","UNADMISSION");body.path("smartstoreChannelProduct").asObject().put("channelProductNo",456);base.removeAll();for(var e:body.properties())base.set(e.getKey(),e.getValue());return response(200,"{\"originProductNo\":123,\"smartstoreChannelProductNo\":456}");});
        var result=writer.execute(1L,prepared,step,null);assertThat(result.mapping().sellerProductId()).isEqualTo("123");assertThat(result.mapping().channelProductId()).isEqualTo("456");assertThat(result.state()).isEqualTo(State.ACCEPTED);assertThat(result.code()).isEqualTo("APPROVAL_PENDING");
        assertThat(writer.execute(1L,prepared,step,result.mapping()).state()).isEqualTo(State.FAILED);verify(gateway,times(1)).write(any(),any(),any());
        base.path("originProduct").asObject().put("statusType","SALE");assertThat(writer.reconcile(1L,prepared,step,result).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void lostCreateResponseCanOnlyLinkOneNewMatchingIdAndSellerCodeSearchUsesDocumentedField(){
        var prepared=writer.prepare(1L,document(),null,false);var step=prepared.steps().getFirst();var registered=json.readTree(step.bodyJson()).deepCopy().asObject();registered.path("originProduct").asObject().put("originProductNo",789);registered.path("smartstoreChannelProduct").asObject().put("channelProductNo",987);
        when(gateway.product("789")).thenReturn(registered);when(gateway.search(any())).thenAnswer(i->{var query=(JsonNode)i.getArgument(0);assertThat(query.path("sellerManagementCode").asString()).isEqualTo("SKU");assertThat(query.has("searchKeyword")).isFalse();return json.readTree("{\"contents\":[{\"originProductNo\":789}],\"totalPages\":1,\"last\":true}");});
        var result=writer.reconcile(1L,prepared,step,new Result(State.UNKNOWN,null,"NETWORK",null,Instant.now()));assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.mapping().sellerProductId()).isEqualTo("789");verify(gateway,never()).write(any(),any(),any());
    }
    @Test void httpRejectionAndLostSuccessRemainDifferentAndNoChangeSendsNothing(){
        var prepared=edit(field(input(),"originProduct.stockQuantity",0L));var step=prepared.steps().getFirst();assertThat(writer.parseOutcome(response(400,"{}"),step,mapping(),input()).state()).isEqualTo(State.FAILED);assertThat(writer.parseOutcome(response(500,"{}"),step,mapping(),input()).state()).isEqualTo(State.UNKNOWN);assertThat(writer.parseOutcome(response(200,"bad-json"),step,mapping(),input()).state()).isEqualTo(State.UNKNOWN);assertThat(edit(input()).steps()).isEmpty();
    }
    @Test void discountAndNoticeHaveTheirOwnFieldsAndInactiveNoticeIsExcluded(){
        var desired=field(field(input(),"originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.value","1000"),"originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.unitType","WON");var prepared=edit(desired);var body=json.readTree(prepared.steps().getFirst().bodyJson());assertThat(body.path("originProduct").path("customerBenefit").path("immediateDiscountPolicy").path("discountMethod").path("value").asInt()).isEqualTo(1000);assertThat(body.path("originProduct").has("originalPrice")).isFalse();
        desired=field(desired,"originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.value",20000);var invalid=desired;assertThatThrownBy(()->edit(invalid)).isInstanceOf(InputValidationFailure.class).hasMessageContaining("할인");
    }
    @Test void clearingDiscountRemovesWholePolicyAndReadbackDoesNotRequireItsInactiveUnit(){
        base.path("originProduct").asObject().set("customerBenefit",json.readTree("{\"immediateDiscountPolicy\":{\"discountMethod\":{\"value\":1000,\"unitType\":\"WON\"}}}"));
        var before=input();var desired=field(field(before,"originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.value",null),"originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.unitType","PERCENT");var prepared=edit(desired);var step=prepared.steps().getFirst();var body=json.readTree(step.bodyJson());assertThat(body.path("originProduct").path("customerBenefit").has("immediateDiscountPolicy")).isFalse();
        base.path("originProduct").asObject().set("customerBenefit",json.createObjectNode());var result=writer.reconcile(1L,prepared,step,new Result(State.ACCEPTED,mapping(),"ACCEPTED",null,Instant.now()));assertThat(result.state()).isEqualTo(State.CONFIRMED);
    }
}
