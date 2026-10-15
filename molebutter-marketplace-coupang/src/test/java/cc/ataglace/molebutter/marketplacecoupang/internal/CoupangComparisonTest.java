package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;
import static org.assertj.core.api.Assertions.*;

class CoupangComparisonTest {
    final ObjectMapper json=new ObjectMapper();
    Document document(){var f=new CoupangRegistrationInputTest();return CoupangRegistrationInput.convert(f.input(List.of(f.option(CoupangRegistrationInputTest.A,"레트 패턴","A"),f.option(CoupangRegistrationInputTest.B,"다른 옵션","B"))));}
    @Test void purchaseAttributesUseNameAndKindAndRejectDuplicates(){
        String path="markets.COUPANG.coupang.options.attributes";
        var a=List.of(new CoupangCatalog.Attribute("색상","레드","EXPOSED"),new CoupangCatalog.Attribute("사이즈","FREE","EXPOSED"));
        var reversed=new ArrayList<>(a);Collections.reverse(reversed);
        assertThat(CoupangComparison.value(new Change(path,"option",a),json)).isEqualTo(CoupangComparison.value(new Change(path,"option",reversed),json));
        assertThat(CoupangComparison.value(new Change(path,"option",a),json)).isNotEqualTo(CoupangComparison.value(new Change(path,"option",List.of(new CoupangCatalog.Attribute("색상","블랙","EXPOSED"))),json));
        assertThatThrownBy(()->CoupangComparison.value(new Change(path,"option",List.of(a.getFirst(),a.getFirst())),json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void inheritedMediaIsComparedOncePerOptionAndEmptyOverridePreservesOtherOptions(){
        var d=document();String option=d.options().getFirst().id();String other=d.options().getLast().id();
        var common=new Image(UUID.randomUUID().toString(),null,"https://source/one",true,0,null,"REPRESENTATION");
        d=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(common),List.of()),d.delivery(),d.selectedMarkets(),d.markets());
        var fields=CoupangComparison.fields(d,json);assertThat(fields).noneMatch(c->c.path().equals("options.name")||c.path().startsWith("media.")&&c.optionId()==null);
        var media=fields.stream().filter(c->c.path().equals("media.images")&&option.equals(c.optionId())).findFirst().orElseThrow();
        assertThat(CoupangComparison.source(d,media)).isEqualTo("COMMON");assertThat((List<?>)media.value()).hasSize(1);
        var empty=CoupangEditPatch.apply(d,List.of(new Change("media.images",option,List.of())),json);
        assertThat(empty.media().images()).containsExactly(common);assertThat(MarketplaceDocuments.imagesFor(empty,option)).isEmpty();assertThat(MarketplaceDocuments.imagesFor(empty,other)).containsExactly(common);
        var restored=json.readValue(json.writeValueAsString(empty),Document.class);assertThat(MarketplaceDocuments.imagesFor(MarketplaceDocuments.normalize(restored),option)).isEmpty();
    }
    @Test void normalPriceHasOneCanonicalSlotAndReadableDefaults(){
        var initial=document();var first=initial.options().getFirst();var options=new ArrayList<>(initial.options());options.set(0,new Option(first.id(),first.name(),first.sku(),first.price(),first.quantity(),first.attributes(),"128000"));var d=new Document(initial.id(),initial.revision(),initial.common(),options,initial.stockMode(),initial.productQuantity(),initial.services(),initial.media(),initial.delivery(),initial.selectedMarkets(),initial.markets());String option=d.options().getFirst().id();String p="markets.COUPANG.coupang.options.registration.originalPrice";
        var price=new Change(p,option,"128000");assertThat(CoupangComparison.display(price,json)).isEqualTo("128,000원");
        assertThat(CoupangComparison.label(new Change("markets.COUPANG.coupang.options.registration.emptyBarcode",option,"true"))).isEqualTo("바코드 없음");
        assertThat(CoupangComparison.display(new Change("markets.COUPANG.coupang.options.registration.emptyBarcode",option,"true"),json)).isEqualTo("예");
        assertThat(CoupangComparison.supported(new Change("common.material",null,"가죽"))).isFalse();
        assertThat(CoupangComparison.supported(new Change("markets.COUPANG.coupang.options.registration.autoPricingInfoView.active",option,"false"))).isFalse();
        assertThat(CoupangComparison.fields(d,json).stream().filter(c->c.path().equals(p)&&option.equals(c.optionId()))).containsExactly(price);
        assertThat(CoupangComparison.known(d,List.of(),d,json)).contains(new ConflictKey(p,option));
        assertThat(CoupangComparison.known(d,List.of(),null,json)).doesNotContain(new ConflictKey(p,option));
    }
}
