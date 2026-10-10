package cc.ataglace.molebutter.marketplacecoupang.internal;


import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.CoupangProductRegistrations.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

class CoupangRegistrationInputTest {
 static final String A="10000000-1000-4000-8000-000000000001",B="10000000-1000-4000-8000-000000000002";
 Option option(String id,String color,String sku){return new Option(id,List.of(new CoupangCatalog.Attribute("사이즈","FREE","EXPOSED"),new CoupangCatalog.Attribute("색상",color,"EXPOSED"),new CoupangCatalog.Attribute("소재",color,"NONE"),new CoupangCatalog.Attribute("Manufacturer Part Number",sku,"NONE"),new CoupangCatalog.Attribute("Global Trade Item Number","GTIN-"+sku,"NONE")),List.of(new Image(null,null,"https://example.test/"+sku+".png","REPRESENTATION",0)),List.of(new Content(null,"HTML","TEXT","<p>"+sku+"</p>")),List.of(new CoupangCatalog.Notice("가방","소재",color),new CoupangCatalog.Notice("기타","품명","제외")),Map.of("salePrice","0","maximumBuyCount","0","externalVendorSku",sku,"searchTags",color,"taxType","TAX"),List.of(new CoupangCatalog.Certification("NOT_REQUIRED","",List.of())),"가방");}
 Input input(List<Option> options){return new Input(Map.of("sellerProductName","신규 상품","brand","브랜드","displayCategoryCode","123"),options,Map.of(),Map.of("brandId","KR-TEST"),List.of());}
 @Test void mappingUsesStableUuidCopiesSharedValuesAndKeepsOptionMediaAndMpn(){
  var document=CoupangRegistrationInput.convert(input(List.of(option(A,"브라운","A"),option(B,"블랙","B"))));
  assertThat(document.options()).extracting(o->o.name()).containsExactly("브라운 FREE","블랙 FREE");
  assertThat(document.options()).extracting(o->o.price()).containsExactly("0","0");
  assertThat(document.markets().get(MarketplaceDrafts.Market.COUPANG).coupang().source()).isNull();
  var restored=CoupangRegistrationInput.input(document);assertThat(restored.options()).extracting(Option::id).containsExactly(A,B);
  var second=restored.options().getLast();assertThat(second.registration().get("searchTags")).isEqualTo("브라운");
  assertThat(second.attributes()).anySatisfy(a->{assertThat(a.name()).isEqualTo("소재");assertThat(a.value()).isEqualTo("브라운");});
  assertThat(second.attributes()).anySatisfy(a->{assertThat(a.name()).isEqualTo("Manufacturer Part Number");assertThat(a.value()).isEqualTo("B");});
  assertThat(second.attributes()).anySatisfy(a->{assertThat(a.name()).isEqualTo("Global Trade Item Number");assertThat(a.value()).isEqualTo("GTIN-B");});
  assertThat(second.notices()).hasSize(1);assertThat(second.notices().getFirst().category()).isEqualTo("가방");assertThat(second.notices().getFirst().content()).isEqualTo("브라운");
  assertThat(second.images().getFirst().url()).endsWith("B.png");assertThat(second.contents().getFirst().content()).isEqualTo("<p>B</p>");
  var reordered=CoupangRegistrationInput.input(CoupangRegistrationInput.convert(input(List.of(option(B,"블랙","B"),option(A,"브라운","A")))));
  assertThat(reordered.options().getFirst().id()).isEqualTo(B);assertThat(reordered.options().getFirst().images().getFirst().url()).endsWith("B.png");
 }
 @Test void incompleteTemporaryInputIsAllowedButForeignIdentityDuplicateUuidAndBlobAreRejected(){
  assertThat(CoupangRegistrationInput.convert(new Input(Map.of(),List.of(new Option(A,List.of(),List.of(),List.of(),List.of(),Map.of(),List.of(),"")),Map.of(),Map.of(),List.of())).options()).hasSize(1);
  assertThatThrownBy(()->CoupangRegistrationInput.convert(input(List.of(option(A,"A","A"),option(A,"B","B"))))).isInstanceOf(InputValidationFailure.class);
  assertThatThrownBy(()->CoupangRegistrationInput.convert(new Input(Map.of("sellerProductId","1"),List.of(option(A,"A","A")),Map.of(),Map.of(),List.of()))).isInstanceOf(InputValidationFailure.class);
  var o=option(A,"A","A");var blob=new Option(A,o.attributes(),List.of(new Image(null,null,"blob:temporary","REPRESENTATION",0)),o.contents(),o.notices(),o.registration(),o.certifications(),"가방");
  assertThatThrownBy(()->CoupangRegistrationInput.convert(input(List.of(blob)))).isInstanceOf(InputValidationFailure.class);
 }
}
