package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoupangProductDocumentsTest {
    private final CoupangBrands brands=mock(CoupangBrands.class);
    private final CoupangProductClient client=mock(CoupangProductClient.class);
    private final CoupangProductDocuments documents=new DefaultCoupangProductDocuments(mock(CoupangEditor.class),brands,client,new ObjectMapper());
    @Test void typedPortKeepsStableOptionsAndResolvedOverridesWithoutCallingTransport(){
        var fixture=new CoupangRegistrationInputTest();var initial=documents.registration(fixture.input(List.of(fixture.option(CoupangRegistrationInputTest.A,"브라운","A"),fixture.option(CoupangRegistrationInputTest.B,"블랙","B"))));
        var config=initial.markets().get(Market.COUPANG);var override=new Overrides("별도 상품명","노출명",null,List.of(new OptionOverride(CoupangRegistrationInputTest.B,null,null,"12000","0")),null,null);
        var input=new Document(initial.id(),initial.revision(),initial.common(),initial.options(),initial.stockMode(),initial.productQuantity(),initial.services(),initial.media(),initial.delivery(),initial.selectedMarkets(),Map.of(Market.COUPANG,new MarketConfig(config.categoryCode(),override,config.coupang(),null,null)));
        var projected=documents.newDocument(input);assertThat(projected.common().name()).isEqualTo("별도 상품명");assertThat(projected.options().getLast().id()).isEqualTo(CoupangRegistrationInputTest.B);assertThat(projected.options().getLast().price()).isEqualTo("12000");assertThat(projected.options().getLast().quantity()).isEqualTo("0");assertThat(projected.markets().get(Market.COUPANG).overrides().productName()).isEqualTo("노출명");
        assertThat(documents.registrationInput(projected).options()).extracting(CoupangProductRegistrations.Option::id).containsExactly(CoupangRegistrationInputTest.A,CoupangRegistrationInputTest.B);verifyNoInteractions(client,brands);
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
}
