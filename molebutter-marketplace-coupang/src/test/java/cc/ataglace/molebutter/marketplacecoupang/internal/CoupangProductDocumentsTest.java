package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoupangProductDocumentsTest {
    private final CoupangEditor editor=mock(CoupangEditor.class);
    private final CoupangBrands brands=mock(CoupangBrands.class);
    private final CoupangProductClient client=mock(CoupangProductClient.class);
    private final CoupangProductDocuments documents=new DefaultCoupangProductDocuments(editor,brands,client,new ObjectMapper(),mock(cc.ataglace.molebutter.media.api.R2Images.class));
    {when(client.registrationUserId()).thenReturn("server-wing");}
    @Test void appOnlyCommonFieldsAreNotReportedAsEmptyRemoteValues(){
        var fixture=new CoupangRegistrationInputTest();var d=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"))));
        assertThat(documents.observedComparisonFields(d)).extracting(MarketplaceEditing.Change::path).doesNotContain("common.origin","common.material","common.afterService");
        assertThat(documents.comparisonFields(d)).extracting(MarketplaceEditing.Change::path).doesNotContain("options.name","common.origin","common.material","common.afterService");
    }
    @Test void inputValidationReportsMissingServerWingSetting(){
        when(client.registrationUserId()).thenReturn("");
        when(editor.category(anyLong(),anyString(),any())).thenReturn(new CoupangEditor.CategoryRules("123",true,List.of(),List.of(),List.of(),List.of(),List.of()));
        var fixture=new CoupangRegistrationInputTest();var d=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"))));
        var errors=new ArrayList<Issue>();documents.validateFields(1L,d,d.markets().get(Market.COUPANG),null,Map.of(),errors,new ArrayList<>());
        assertThat(errors).anySatisfy(issue->assertThat(issue.message()).contains("coupang_vendor_user_id"));
        verify(client,never()).writeSession(any());
    }
    @Test void addressChoicesBecomeEditableDeliveryDefaultsWithoutStaleOverrides(){
        var fixture=new CoupangRegistrationInputTest();var initial=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"))));
        var c=initial.markets().get(Market.COUPANG);
        var book=new AddressBook(new AddressSelection("101","출고","12345","서울","1층","01000000000"),new AddressSelection("102","반품","12345","부산","2층","01000000000"));
        var ref=new Document(initial.id(),initial.revision(),initial.common(),initial.options(),initial.stockMode(),initial.productQuantity(),initial.services(),initial.media(),initial.delivery(),initial.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(c.categoryCode(),c.overrides(),c.coupang(),null,null,null,book)));
        var json=new ObjectMapper();var restored=json.readValue(json.writeValueAsString(ref),Document.class);
        var normalized=MarketplaceDocuments.normalize(restored);assertThat(normalized.markets().get(Market.COUPANG).addressBook()).isEqualTo(book);
        var projected=documents.newDocument(normalized);
        assertThat(projected.delivery().outboundCode()).isEqualTo("101");assertThat(projected.delivery().returnCode()).isEqualTo("102");assertThat(projected.delivery().returnAddress()).isEqualTo("부산");
        assertThat(projected.markets().get(Market.COUPANG).addressBook()).isNull();
        assertThat(DraftCoupangProjection.delivery(projected,projected.markets().get(Market.COUPANG))).containsEntry("outboundShippingPlaceCode","101").containsEntry("returnCenterCode","102");
        assertThat(initial.delivery().outboundCode()).isNotEqualTo("101");verifyNoInteractions(client,brands);
    }
    @Test void typedPortKeepsStableOptionsAndResolvedOverridesWithoutCallingTransport(){
        var fixture=new CoupangRegistrationInputTest();var initial=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"),fixture.option(CoupangRegistrationInputTest.B,"블랙","B"))));
        var config=initial.markets().get(Market.COUPANG);var override=new Overrides("별도 상품명","노출명",null,List.of(new OptionOverride(CoupangRegistrationInputTest.B,null,null,"12000","0")),null,null);
        var input=new Document(initial.id(),initial.revision(),initial.common(),initial.options(),initial.stockMode(),initial.productQuantity(),initial.services(),initial.media(),initial.delivery(),initial.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(config.categoryCode(),override,config.coupang(),null,null)));
        var projected=documents.newDocument(input);assertThat(projected.common().name()).isEqualTo("별도 상품명");assertThat(projected.options().getLast().id()).isEqualTo(CoupangRegistrationInputTest.B);assertThat(projected.options().getLast().price()).isEqualTo("12000");assertThat(projected.options().getLast().quantity()).isEqualTo("0");assertThat(projected.markets().get(Market.COUPANG).overrides().productName()).isEqualTo("노출명");
        assertThat(documents.registrationInput(projected).options()).extracting(CoupangProductRegistrations.Option::id).containsExactly(CoupangRegistrationInputTest.A,CoupangRegistrationInputTest.B);verifyNoInteractions(client,brands);
    }
    @Test void commonNameDefaultsBothCoupangNamesWithoutReplacingGeneralProductName(){
        var fixture=new CoupangRegistrationInputTest();var initial=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"))));
        var c=initial.markets().get(Market.COUPANG);
        var input=new Document(initial.id(),initial.revision(),initial.common(),initial.options(),initial.stockMode(),initial.productQuantity(),initial.services(),initial.media(),initial.delivery(),initial.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(c.categoryCode(),new Overrides(null,null,null,List.of(),null,null),c.coupang(),null,null)));
        var projected=documents.newDocument(input);
        assertThat(projected.common().name()).isEqualTo(initial.common().name());
        assertThat(projected.markets().get(Market.COUPANG).overrides().productName()).isEqualTo(initial.common().name());
        assertThat(projected.common().productName()).isEqualTo(initial.common().productName());
        verifyNoInteractions(client,brands);
    }
    @Test void missingSellerSkuStaysEmptyInDocumentAndRegistrationInput(){
        var f=new CoupangRegistrationInputTest();var initial=documents.registration(f.input(List.of(f.option(CoupangRegistrationInputTest.A,"브라운","A"))));
        var o=initial.options().getFirst();var empty=new Option(o.id(),o.name(),"",o.price(),o.quantity(),o.attributes());
        var input=new Document(initial.id(),initial.revision(),initial.common(),List.of(empty),initial.stockMode(),initial.productQuantity(),initial.services(),initial.media(),initial.delivery(),initial.selectedMarkets(),initial.markets());
        var projected=documents.newDocument(input);
        assertThat(projected.options().getFirst().sku()).isEmpty();
        assertThat(DraftCoupangProjection.registration(projected,projected.markets().get(Market.COUPANG),projected.options().getFirst()).get("externalVendorSku")).isEmpty();
        assertThat(documents.registrationInput(projected).options().getFirst().registration().get("externalVendorSku")).isEmpty();
        verifyNoInteractions(client,brands);
    }
    @Test void brandProofRemainsRequiredAtCorePortBoundary(){
        var fixture=new CoupangRegistrationInputTest();var document=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"))));documents.requireBrand(1L,null,document);verify(brands).requireSelection(1L,"KR-TEST","브랜드");
    }
    @Test void forgedExternalIdentityAndMissingSelectedConfigurationAreRejected(){
        var fixture=new CoupangRegistrationInputTest();var draft=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"))));var config=draft.markets().get(Market.COUPANG);var c=config.coupang();
        var forged=new Coupang("123",c.delivery(),c.settings(),c.options(),c.documents(),null);var linked=new Document(draft.id(),draft.revision(),draft.common(),draft.options(),draft.stockMode(),draft.productQuantity(),draft.services(),draft.media(),draft.delivery(),draft.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(config.categoryCode(),config.overrides(),forged,null,null)));
        assertThatThrownBy(()->documents.legacyMapping(linked,"account")).isInstanceOf(MarketplaceEditingFailure.class);
        var missing=new Document(draft.id(),draft.revision(),draft.common(),draft.options(),draft.stockMode(),draft.productQuantity(),draft.services(),draft.media(),draft.delivery(),draft.selectedMarkets(),Map.of());
        assertThatThrownBy(()->documents.requireBrand(1L,null,missing)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);verifyNoInteractions(client,brands);
    }
    @Test void createProjectionMapsCommonPurchaseAttributesByNameAndKeepsIndependentSearchValues(){
        var fixture=new CoupangRegistrationInputTest();var initial=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"),fixture.option(CoupangRegistrationInputTest.B,"블랙","B"))));
        var a=initial.options().getFirst();var b=initial.options().getLast();
        var commonOptions=List.of(new Option(b.id(),b.name(),b.sku(),b.price(),b.quantity(),List.of(new Attribute("색상","공통 화이트"),new Attribute("사이즈","XL"),new Attribute("새 구매속성","값"))),a);
        var input=new Document(initial.id(),initial.revision(),initial.common(),commonOptions,initial.stockMode(),initial.productQuantity(),initial.services(),initial.media(),initial.delivery(),initial.selectedMarkets(),initial.markets());
        var projected=documents.newDocument(input);var first=projected.markets().get(Market.COUPANG).coupang().options().getFirst();
        assertThat(first.optionId()).isEqualTo(b.id());assertThat(first.attributes()).anySatisfy(v->{assertThat(v.name()).isEqualTo("색상");assertThat(v.value()).isEqualTo("공통 화이트");assertThat(v.exposed()).isEqualTo("EXPOSED");});
        assertThat(first.attributes()).anySatisfy(v->{assertThat(v.name()).isEqualTo("사이즈");assertThat(v.value()).isEqualTo("XL");});
        assertThat(first.attributes()).anySatisfy(v->{assertThat(v.name()).isEqualTo("새 구매속성");assertThat(v.value()).isEqualTo("값");assertThat(v.exposed()).isEqualTo("EXPOSED");});
        assertThat(first.attributes()).anySatisfy(v->{assertThat(v.name()).isEqualTo("소재");assertThat(v.value()).isEqualTo("브라운");assertThat(v.exposed()).isEqualTo("NONE");});
        assertThat(first.attributes()).anySatisfy(v->{assertThat(v.name()).isEqualTo("Manufacturer Part Number");assertThat(v.value()).isEqualTo("B");});
        assertThat(initial.markets().get(Market.COUPANG).coupang().options().getLast().attributes()).anySatisfy(v->{assertThat(v.name()).isEqualTo("색상");assertThat(v.value()).isEqualTo("블랙");});verifyNoInteractions(client,brands);
    }
    @Test void categoryCanBeSavedForCreateButCannotAlterAnExistingListing(){
        var f=new CoupangRegistrationInputTest();var d=documents.registration(f.input(List.of(f.option(CoupangRegistrationInputTest.A,"브라운","A"))));
        var changes=List.of(new MarketplaceEditing.Change("markets.COUPANG.categoryCode",null,"123"));
        assertThat(documents.apply(d,changes).markets().get(Market.COUPANG).categoryCode()).isEqualTo("123");
        var config=d.markets().get(Market.COUPANG);var cp=config.coupang();var linked=new Coupang("999",cp.delivery(),cp.settings(),cp.options(),cp.documents(),cp.source());
        var existing=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(config.categoryCode(),config.overrides(),linked,null,null)));
        assertThatThrownBy(()->documents.apply(existing,changes)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        var field=new MarketplaceEditing.Change("options.quantity",CoupangRegistrationInputTest.A,"0");var latest=documents.apply(existing,List.of(new MarketplaceEditing.Change("options.quantity",CoupangRegistrationInputTest.A,"10")));
        assertThat(documents.rebase(existing,latest,List.of(field)).conflicts()).hasSize(1);assertThat(documents.rebase(existing,documents.apply(existing,List.of(field)),List.of(field)).changes()).isEmpty();verifyNoInteractions(client,brands);
    }


    @Test void fieldValidationLeavesActualFileReadingToSharedInspector(){
        var editor=mock(CoupangEditor.class);var r2=mock(cc.ataglace.molebutter.media.api.R2Images.class);var provider=new DefaultCoupangProductDocuments(editor,brands,client,new ObjectMapper(),r2);
        var fixture=new CoupangRegistrationInputTest();var d=provider.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"))));var config=d.markets().get(Market.COUPANG);
        when(editor.category(anyLong(),anyString(),isNull())).thenReturn(new CoupangEditor.CategoryRules(config.categoryCode(),true,List.of(),List.of(),List.of(),List.of(),List.of()));
        provider.validateFields(1L,d,config,null,Map.of(),new ArrayList<>(),new ArrayList<>());verifyNoInteractions(r2);
    }
}
