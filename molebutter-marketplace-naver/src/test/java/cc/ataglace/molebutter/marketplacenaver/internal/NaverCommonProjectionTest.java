package cc.ataglace.molebutter.marketplacenaver.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverCommonProjectionTest {
    static final String P="originProduct.",D=P+"detailAttribute.",S="smartstoreChannelProduct.";
    final String black=UUID.randomUUID().toString(),white=UUID.randomUUID().toString();
    final NaverCatalog catalog=mock(NaverCatalog.class);
    final NaverGateway gateway=mock(NaverGateway.class);
    final DefaultNaverProductDocuments documents=new DefaultNaverProductDocuments(catalog,gateway);
    EditorState state(List<String> overrides,String pricing,String shape,String images,String imageOption,String description,String descriptionOption){return new EditorState(1,overrides,pricing,shape,images,imageOption,description,descriptionOption,List.of());}
    NaverEditor.Input input(Document d){return documents.input(d);}
    Document common(NaverEditor.Input input,EditorState state,Media media,StockMode stock){
        var n=new Naver("legacy-channel","SALE","NEW","01","legacy-template","01000000000",List.of(new Attribute("색상","검정")),List.of(new Attribute("제조자","LF")),"채널명",true,"ON",input,"9007199254740993",state);
        var over=new Overrides(null,null,"DAKS",List.of(),null,null);
        return new Document("10",7L,new Common("CODE","공통 상품명","제품명","닥스","LF","한국","가죽","모델","A/S 안내","FREE","ADULT_ONLY"),List.of(new Option(black,"검정","B","2000","0",List.of()),new Option(white,"흰색","W","1000","3",List.of())),stock,"9",List.of(),media,null,List.of(Market.COUPANG,Market.NAVER),Map.of(Market.COUPANG,new MarketConfig("cp",new Overrides(null,null,null,List.of(),null,null),null,null,null),Market.NAVER,new MarketConfig("50000000",over,null,n,null)));
    }
    Media media(){return new Media(List.of(),List.of());}
    @Test void selectedAddressBookDefaultsAreScopedAndPreserveNativeOverrides(){
        var original=common(null,null,media(),StockMode.OPTION);var config=original.markets().get(Market.NAVER);
        var address=new AddressSelection("201","출고지","12345","서울","1층","01000000000");
        var book=new AddressBook(address,new AddressSelection("202","반품지","12345","서울","2층","01000000000"));
        var selected=new MarketConfig(config.categoryCode(),config.overrides(),null,config.naver(),null,null,book);
        var ref=new Document(original.id(),original.revision(),original.common(),original.options(),original.stockMode(),original.productQuantity(),original.services(),original.media(),original.delivery(),original.selectedMarkets(),Map.of(Market.NAVER,selected));
        var projected=documents.newDocument(MarketplaceDocuments.normalize(ref));
        assertThat(projected.markets().get(Market.NAVER).addressBook()).isEqualTo(book);
        assertThat(input(projected).fields()).containsEntry(P+"deliveryInfo.claimDeliveryInfo.shippingAddressId",201L).containsEntry(P+"deliveryInfo.claimDeliveryInfo.returnAddressId",202L);
        var old=input(projected);var fields=new HashMap<String,Object>(old.fields());fields.put(P+"deliveryInfo.claimDeliveryInfo.shippingAddressId",999L);
        var edited=NaverDraftAdapter.withInput(projected,new NaverEditor.Input(fields,old.optionMode(),old.optionNames(),old.options(),old.images(),old.description()),state(List.of("fields."+P+"deliveryInfo.claimDeliveryInfo.shippingAddressId"),"COMMON","COMMON",null,null,null,null));
        assertThat(input(documents.newDocument(edited)).fields()).containsEntry(P+"deliveryInfo.claimDeliveryInfo.shippingAddressId",999L).containsEntry(P+"deliveryInfo.claimDeliveryInfo.returnAddressId",202L);
        verifyNoInteractions(catalog,gateway);
    }
    @Test void commonNameDefaultsBothOriginAndChannelProductNames(){
        var d=common(null,null,media(),StockMode.OPTION);var config=d.markets().get(Market.NAVER);
        var input=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.NAVER,new MarketConfig(config.categoryCode(),config.overrides(),null,null,null)));
        var projected=documents.newDocument(input);
        assertThat(input(projected).fields()).containsEntry(P+"name",d.common().name()).containsEntry(S+"channelProductName",d.common().name());
        var old=input(projected);var fields=new HashMap<String,Object>(old.fields());fields.put(S+"channelProductName","개별 노출명");
        var custom=new NaverEditor.Input(fields,old.optionMode(),old.optionNames(),old.options(),old.images(),old.description());
        var edited=documents.newDocument(common(custom,state(List.of("fields."+S+"channelProductName"),"COMMON","COMMON",null,null,null,null),media(),StockMode.OPTION));
        assertThat(input(edited).fields()).containsEntry(S+"channelProductName","개별 노출명").containsEntry(P+"name",d.common().name());
        verifyNoInteractions(catalog,gateway);
    }
    @Test void categoryDefaultsAreReappliedUnlessExplicitlyOverridden(){
        var old=new NaverEditor.Input(Map.of(P+"leafCategoryId","50000123"),"NONE",List.of(),List.of(),List.of(),"");
        var inherited=documents.newDocument(common(old,state(List.of(),"COMMON","COMMON",null,null,null,null),media(),StockMode.OPTION));
        assertThat(input(inherited).fields()).containsEntry(P+"leafCategoryId","50000000");
        var custom=documents.newDocument(common(old,state(List.of("fields."+P+"leafCategoryId"),"COMMON","COMMON",null,null,null,null),media(),StockMode.OPTION));
        assertThat(input(custom).fields()).containsEntry(P+"leafCategoryId","50000123");
    }
    @Test void commonProjectionUsesMinimumAbsolutePriceAndStableOptionIdsWithoutReplacingReference(){
        var original=common(null,null,media(),StockMode.OPTION);var projected=documents.newDocument(original);var value=input(projected);
        assertThat(documents.supports(original)).isTrue();assertThat(projected.common()).isSameAs(original.common());assertThat(projected.options()).isSameAs(original.options());assertThat(projected.selectedMarkets()).containsExactly(Market.COUPANG,Market.NAVER);assertThat(projected.markets().get(Market.COUPANG)).isSameAs(original.markets().get(Market.COUPANG));
        assertThat(value.fields()).containsEntry(P+"salePrice",1000L).containsEntry(P+"stockQuantity",3L).containsEntry(D+"taxType","DUTYFREE").containsEntry(D+"minorPurchasable",false).containsEntry(D+"sellerCodeInfo.sellerManagementCode","CODE");
        assertThat(value.optionNames()).containsExactly("옵션");assertThat(value.options()).extracting(NaverEditor.Option::id).containsExactly(black,white);assertThat(value.options()).extracting(NaverEditor.Option::price).containsExactly(1000L,0L);assertThat(value.options()).extracting(NaverEditor.Option::stockQuantity).containsExactly(0L,3L);
        assertThat(value.options().getFirst().values()).containsExactly("검정");assertThat(value.options().getFirst().sellerManagerCode()).isEqualTo("B");assertThat(value.fields()).containsEntry(D+"naverShoppingSearchInfo.brandName","DAKS").containsEntry(D+"naverShoppingSearchInfo.brandId",9007199254740993L);
        assertThat(value.fields()).doesNotContainKey("originProduct.originProductNo");verifyNoInteractions(catalog,gateway);
    }
    @Test void customPriceGroupAndExplicitBlankOrZeroSurviveCommonChanges(){
        var nativeInput=new NaverEditor.Input(Map.of(P+"name","",P+"salePrice",5000L),"COMBINATION",List.of("옵션"),List.of(new NaverEditor.Option(black,List.of("직접 검정"),250L,0L,"",true),new NaverEditor.Option(white,List.of("직접 흰색"),750L,8L,"W2",true)),List.of(),"");
        var s=state(List.of("fields."+P+"name","options."+black+".values","options."+black+".stockQuantity","options."+black+".sellerManagerCode"),"CUSTOM","COMMON",null,null,null,null);
        var value=input(documents.newDocument(common(nativeInput,s,media(),StockMode.OPTION)));
        assertThat(value.fields()).containsEntry(P+"name","").containsEntry(P+"salePrice",5000L);assertThat(value.options()).extracting(NaverEditor.Option::price).containsExactly(250L,750L);assertThat(value.options().getFirst().values()).containsExactly("직접 검정");assertThat(value.options().getFirst().stockQuantity()).isZero();assertThat(value.options().getFirst().sellerManagerCode()).isEmpty();
    }
    @Test void productTotalIsNeverCopiedToEveryCombination(){
        var value=input(documents.newDocument(common(null,null,media(),StockMode.PRODUCT)));
        assertThat(value.options()).allSatisfy(o->assertThat(o.stockQuantity()).isNull());assertThat(value.fields()).containsEntry(P+"stockQuantity",null);
    }
    @Test void mediaSourcesAreIndependentAndMissingCommonDoesNotPickTheFirstOption(){
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        var media=new Media(List.of(new Image(a,null,"https://example.com/common.jpg",true,0,null),new Image(b,null,"https://example.com/option.jpg",true,0,white)),List.of(new Content(UUID.randomUUID().toString(),"HTML","<p>옵션 설명</p>",white)));
        var original=common(null,null,media,StockMode.OPTION);var projected=documents.newDocument(original);assertThat(input(projected).images()).extracting(NaverEditor.Image::id).containsExactly(a);assertThat(input(projected).description()).isEmpty();assertThat(projected.markets().get(Market.NAVER).naver().editorState().descriptionSource()).isNull();assertThatThrownBy(()->NaverCommonProjection.requireMediaSources(projected)).isInstanceOf(InputValidationFailure.class);
        var selected=state(List.of(),"COMMON","COMMON","COMMON",null,"OPTION",white);var next=documents.newDocument(common(null,selected,media,StockMode.OPTION));assertThat(input(next).images()).extracting(NaverEditor.Image::id).containsExactly(a);assertThat(input(next).description()).isEqualTo("<p>옵션 설명</p>");assertThatCode(()->NaverCommonProjection.requireMediaSources(next)).doesNotThrowAnyException();
    }
    @Test void deletedMediaSourceKeepsNativeSnapshotUntilExplicitChoice(){
        String deleted=UUID.randomUUID().toString();var snapshot=new NaverEditor.Input(Map.of(),"COMBINATION",List.of("옵션"),List.of(),List.of(new NaverEditor.Image(UUID.randomUUID().toString(),null,"https://example.com/kept.jpg",true,0)),"<p>보존</p>");
        var s=state(List.of(),"COMMON","COMMON","OPTION",deleted,"CUSTOM",null);var projected=documents.newDocument(common(snapshot,s,media(),StockMode.OPTION));assertThat(input(projected).images()).isEqualTo(snapshot.images());assertThat(input(projected).description()).isEqualTo(snapshot.description());assertThatThrownBy(()->NaverCommonProjection.requireMediaSources(projected)).isInstanceOf(InputValidationFailure.class);
    }
    @Test void commonDescriptionRetainsTextHtmlAndImageOrder(){
        var contents=List.of(new Content(UUID.randomUUID().toString(),"TEXT","<설명> & \"표기\"\n다음 줄",null),new Content(UUID.randomUUID().toString(),"HTML","<p>원본 HTML</p>",null),new Content(UUID.randomUUID().toString(),"IMAGE","https://example.com/image.jpg?a=1&b=2",null));
        var projected=documents.newDocument(common(null,null,new Media(List.of(),contents),StockMode.OPTION));
        assertThat(input(projected).description()).isEqualTo("<p>&lt;설명&gt; &amp; &quot;표기&quot;<br>다음 줄</p>\n<p>원본 HTML</p>\n<img src=\"https://example.com/image.jpg?a=1&amp;b=2\" alt=\"\">");
    }
    @Test void legacyPreparationIsRetainedAndUnknownMappingsAreReported(){
        var original=common(null,null,media(),StockMode.OPTION);var projected=documents.newDocument(original);var n=projected.markets().get(Market.NAVER).naver();
        assertThat(n.attributes()).isEqualTo(original.markets().get(Market.NAVER).naver().attributes());assertThat(n.notices()).isEqualTo(original.markets().get(Market.NAVER).naver().notices());assertThat(n.channelId()).isEqualTo("legacy-channel");assertThat(n.deliveryTemplateId()).isEqualTo("legacy-template");assertThat(n.editorState().unmapped()).contains("attributes","notices","channelId","deliveryTemplateId");assertThat(input(projected).fields()).containsEntry(D+"originAreaInfo.originAreaCode","01").containsEntry(S+"channelProductName","채널명");assertThat(input(projected).fields()).doesNotContainKeys(D+"productAttributes",D+"productInfoProvidedNotice");
    }
    @Test void oldNativeSnapshotWithoutInheritanceMetadataIsNotOverwritten(){
        var old=new NaverEditor.Input(Map.of(P+"name","기존 개별값",P+"salePrice",0L),"NONE",List.of(),List.of(),List.of(),"");var projected=documents.newDocument(common(old,null,media(),StockMode.OPTION));assertThat(input(projected).fields()).containsEntry(P+"name","기존 개별값").containsEntry(P+"salePrice",0L);assertThat(input(projected).optionMode()).isEqualTo("NONE");assertThat(projected.markets().get(Market.NAVER).naver().editorState()).isNull();
    }
    @Test void patchAndSaveTargetPreserveEveryNonNaverField(){
        var original=documents.newDocument(common(null,null,media(),StockMode.OPTION));var changes=List.of(new MarketplaceEditing.Change(NaverEditPatch.PREFIX+"fields."+P+"name",null,"네이버만 수정"));var changed=documents.apply(original,changes);var saved=documents.saveTarget(original,changed);
        assertThat(input(saved).fields()).containsEntry(P+"name","네이버만 수정");assertThat(saved.common()).isSameAs(original.common());assertThat(saved.options()).isSameAs(original.options());assertThat(saved.markets().get(Market.COUPANG)).isSameAs(original.markets().get(Market.COUPANG));assertThat(original.options().getFirst().price()).isEqualTo("2000");
    }
    @Test void pendingRebaseDropsAlreadyAppliedChangesAndReportsOnlyConflictingSelectedFields(){
        var before=documents.newDocument(common(null,null,media(),StockMode.OPTION));var change=new MarketplaceEditing.Change(NaverEditPatch.PREFIX+"fields."+P+"name",null,"내 수정");var applied=documents.apply(before,List.of(change));assertThat(documents.rebase(before,applied,List.of(change)).changes()).isEmpty();
        var latest=documents.apply(before,List.of(new MarketplaceEditing.Change(change.path(),null,"외부 수정")));var result=documents.rebase(before,latest,List.of(change));assertThat(result.changes()).hasSize(1);assertThat(result.conflicts()).singleElement().satisfies(c->{assertThat(c.before()).isEqualTo("공통 상품명");assertThat(c.current()).isEqualTo("외부 수정");assertThat(c.desired()).isEqualTo("내 수정");});
        assertThat(documents.rebase(before,latest,List.of()).conflicts()).isEmpty();
    }
    @Test void safeObservationContextUsesServerRemoteMappingAndDoesNotStoreItInEditorInput(){
        String remoteLocal=UUID.randomUUID().toString();var nativeInput=new NaverEditor.Input(Map.of(P+"name","최신"),"COMBINATION",List.of("색상"),List.of(new NaverEditor.Option(remoteLocal,List.of("검정"),0L,3L,"",true)),List.of(),"");var limits=new NaverEditor.Limits(false,false,true,true,"");var view=new NaverEditor.EditorDocument(nativeInput,limits,List.of(new NaverEditor.OptionIdentity(remoteLocal,"77")),"123","456");
        when(catalog.editor(1L,"123")).thenReturn(view);var mapping=new MarketplaceWriteGateway.Mapping("account","123",List.of(new MarketplaceWriteGateway.OptionMapping(black,"77",null)),"456");var observed=documents.observeTarget(1L,common(null,null,media(),StockMode.OPTION),mapping);
        assertThat(observed.naverContext().limits()).isEqualTo(limits);assertThat(observed.naverContext().optionIdentities()).containsExactly(new NaverEditor.OptionIdentity(black,"77"));assertThat(input(observed.document()).options().getFirst().id()).isEqualTo(black);assertThat(input(observed.document()).fields()).doesNotContainKeys("originProduct.originProductNo","originProduct.detailAttribute.optionInfo.optionCombinations");verify(catalog,times(1)).editor(1L,"123");verifyNoInteractions(gateway);
    }
    @Test void forgedOptionIdentityAndUnsupportedOverrideAreRejected(){
        assertThatThrownBy(()->documents.normalize(common(null,state(List.of("fields.originProduct.originProductNo"),"COMMON","COMMON",null,null,null,null),media(),StockMode.OPTION))).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->documents.apply(documents.newDocument(common(null,null,media(),StockMode.OPTION)),List.of(new MarketplaceEditing.Change(NaverEditPatch.PREFIX+"fields.originProduct.originProductNo",null,"999")))).isInstanceOf(InputValidationFailure.class);
    }

    @Test void normalPriceAndManualDiscountSurviveServerProjectionAndRoundTrip(){
        String discount=P+"customerBenefit.immediateDiscountPolicy.discountMethod.";var original=common(null,null,media(),StockMode.OPTION);
        var fields=new LinkedHashMap<String,Object>();fields.put(P+"salePrice",128000L);fields.put(discount+"value",41000L);fields.put(discount+"unitType","WON");var nativeInput=new NaverEditor.Input(fields,"COMBINATION",List.of("옵션"),List.of(),List.of(),"");
        var n=original.markets().get(Market.NAVER).naver();var config=original.markets().get(Market.NAVER);var updated=new Naver(n.channelId(),n.status(),n.saleType(),n.originCode(),n.deliveryTemplateId(),n.afterServiceTelephone(),n.attributes(),n.notices(),n.channelProductName(),n.naverShoppingRegistration(),n.channelProductDisplayStatusType(),nativeInput,n.brandId(),state(List.of(),"COMMON","COMMON","COMMON",null,"COMMON",null));
        var ref=new Document(original.id(),original.revision(),original.common(),List.of(new Option(black,"옵션","CODE","87000","50",List.of(),"128000")),original.stockMode(),original.productQuantity(),original.services(),original.media(),original.delivery(),original.selectedMarkets(),Map.of(Market.NAVER,new MarketConfig(config.categoryCode(),config.overrides(),null,updated,null)));
        var projected=documents.newDocument(ref);var value=input(projected);assertThat(value.fields()).containsEntry(P+"salePrice",128000L);assertThat(((Number)value.fields().get(discount+"value")).longValue()).isEqualTo(41000L);assertThat(value.options().getFirst().price()).isZero();
        var json=new tools.jackson.databind.ObjectMapper();assertThat(input(documents.newDocument(json.readValue(json.writeValueAsString(projected),Document.class)))).isEqualTo(value);
        fields.put(P+"salePrice",150000L);fields.put(discount+"value",42);fields.put(discount+"unitType","PERCENT");var higher=documents.apply(projected,List.of(new MarketplaceEditing.Change(NaverEditPatch.PREFIX+"fields."+P+"salePrice",null,150000L),new MarketplaceEditing.Change(NaverEditPatch.PREFIX+"fields."+discount+"value",null,42),new MarketplaceEditing.Change(NaverEditPatch.PREFIX+"fields."+discount+"unitType",null,"PERCENT")));
        var next=input(documents.newDocument(higher));assertThat(next.fields()).containsEntry(P+"salePrice",150000L);assertThat(next.options().getFirst().price()).isZero();
        var fractional=documents.apply(higher,List.of(new MarketplaceEditing.Change(NaverEditPatch.PREFIX+"fields."+discount+"value",null,"0.0001")));assertThatThrownBy(()->documents.newDocument(fractional)).isInstanceOf(InputValidationFailure.class);
    }
}
