package cc.ataglace.molebutter.marketplacenaver.internal;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverOptionStockWriteTest {
    final NaverWriteGatewayTest f=new NaverWriteGatewayTest();
    NaverOptionStockWriteTest(){
        var info=f.json.createObjectNode();info.set("optionCombinationGroupNames",f.json.createObjectNode().put("optionGroupName1","color"));
        info.set("optionCombinations",f.json.readTree("[{\"id\":7,\"optionName1\":\"A\",\"price\":100,\"stockQuantity\":5,\"usable\":false,\"sellerManagerCode\":\"A\"},{\"id\":8,\"optionName1\":\"B\",\"price\":200,\"stockQuantity\":5,\"usable\":true,\"sellerManagerCode\":\"B\"}]"));
        f.base.path("originProduct").path("detailAttribute").asObject().set("optionInfo",info);
    }
    NaverEditor.Input input(){return f.input();}
    Mapping mapping(){var options=input().options();return new Mapping("naver-account","123",List.of(new OptionMapping(options.get(0).id(),"7",null),new OptionMapping(options.get(1).id(),"8",null)),"456");}
    NaverEditor.Input stock(NaverEditor.Input before,long value){var a=before.options().getFirst();var rows=new ArrayList<>(before.options());rows.set(0,new NaverEditor.Option(a.id(),a.values(),a.price(),value,a.sellerManagerCode(),a.usable()));return new NaverEditor.Input(before.fields(),before.optionMode(),before.optionNames(),rows,before.images(),before.description());}
    Prepared prepare(NaverEditor.Input before,NaverEditor.Input after){var d=NaverDraftAdapter.document("1",0L,before);return f.writer.prepareSelected(1L,d,mapping(),false,d,NaverEditPatch.diff(before,after));}
    JsonNode row(int i){return f.base.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations").get(i);}
    void apply(JsonNode body){for(var changed:body.path("optionInfo").path("optionCombinations"))for(int i=0;i<2;i++)if(row(i).path("id").asLong()==changed.path("id").asLong())for(var field:changed.properties())row(i).asObject().set(field.getKey(),field.getValue());}
    @Test void nameOnlyUpdateIsBlockedBeforeWritingOrUploading(){var before=input();assertThatThrownBy(()->prepare(before,f.field(before,"originProduct.name","changed"))).isInstanceOf(InputValidationFailure.class).hasMessageContaining("재고");verify(f.gateway,never()).write(any(),any(),any());verify(f.gateway,never()).uploadImage(anyLong(),any());}
    @Test void selectedStockUsesOnlyTheSelectedRemoteOption(){var before=input();var step=prepare(before,stock(before,3)).steps().getFirst();assertThat(step.type()).isEqualTo(Type.STOCK);assertThat(step.path()).isEqualTo("/v1/products/origin-products/123/option-stock");var body=f.json.readTree(step.bodyJson());assertThat(body.has("originProduct")).isFalse();var rows=body.path("optionInfo").path("optionCombinations");assertThat(rows.size()).isEqualTo(1);assertThat(rows.get(0).path("id").asLong()).isEqualTo(7);assertThat(rows.get(0).path("stockQuantity").asLong()).isEqualTo(3);assertThat(rows.get(0).path("price").asLong()).isEqualTo(100);assertThat(rows.get(0).path("usable").asBoolean()).isFalse();}
    @Test void orderOnAnotherOptionBeforeOrDuringDispatchIsPreserved(){var before=input();row(1).asObject().put("stockQuantity",4);var prepared=prepare(before,stock(before,3));row(1).asObject().put("stockQuantity",3);when(f.gateway.write(any(),any(),any())).thenAnswer(i->{row(1).asObject().put("stockQuantity",2);apply(i.getArgument(2));return f.response(200,"{\"originProductNo\":123}");});var actual=new AtomicReference<Step>();var result=f.writer.execute(1L,prepared,prepared.steps().getFirst(),mapping(),actual::set);assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(row(0).path("stockQuantity").asLong()).isEqualTo(3);assertThat(row(1).path("stockQuantity").asLong()).isEqualTo(2);assertThat(f.json.readTree(actual.get().bodyJson()).path("optionInfo").path("optionCombinations").size()).isEqualTo(1);assertThat(f.writer.execute(1L,prepared,prepared.steps().getFirst(),mapping()).state()).isEqualTo(State.CONFIRMED);verify(f.gateway,times(1)).write(any(),any(),any());}
    @Test void selectedStockConflictFailsBeforeDispatch(){var before=input();var prepared=prepare(before,stock(before,3));row(0).asObject().put("stockQuantity",4);var result=f.writer.execute(1L,prepared,prepared.steps().getFirst(),mapping());assertThat(result.state()).isEqualTo(State.FAILED);verify(f.gateway,never()).write(any(),any(),any());}
    @Test void lostResponseIsReadBackWithoutResendingOrComparingOtherStocks(){var before=input();var prepared=prepare(before,stock(before,0));when(f.gateway.write(any(),any(),any())).thenAnswer(i->{apply(i.getArgument(2));throw new MarketplaceFailure(MarketplaceFailure.Kind.NETWORK,"NAVER");});var actual=new AtomicReference<Step>();var unknown=f.writer.execute(1L,prepared,prepared.steps().getFirst(),mapping(),actual::set);assertThat(unknown.state()).isEqualTo(State.UNKNOWN);row(1).asObject().put("stockQuantity",1);var stored=f.json.readValue(f.json.writeValueAsString(actual.get()),Step.class);assertThat(f.writer.reconcile(1L,prepared,stored,unknown).state()).isEqualTo(State.CONFIRMED);verify(f.gateway,times(1)).write(any(),any(),any());}
    @Test void latestUnselectedAdditionalPriceAndUsableAreKeptOnTheChangedStockRow(){
        var before=input();var prepared=prepare(before,stock(before,3));row(0).asObject().put("price",300).put("usable",true);
        when(f.gateway.write(any(),any(),any())).thenAnswer(i->{var body=(JsonNode)i.getArgument(2);var changed=body.path("optionInfo").path("optionCombinations").get(0);assertThat(changed.path("price").asLong()).isEqualTo(300);assertThat(changed.path("usable").asBoolean()).isTrue();apply(body);return f.response(200,"{\"originProductNo\":123}");});
        assertThat(f.writer.execute(1L,prepared,prepared.steps().getFirst(),mapping()).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void changedOptionIdentityAndInvalidQuantityCannotDispatch(){
        var before=input();assertThatThrownBy(()->prepare(before,stock(before,100000000))).isInstanceOf(InputValidationFailure.class);
        var prepared=prepare(before,stock(before,3));row(0).asObject().put("sellerManagerCode","OTHER");
        assertThat(f.writer.execute(1L,prepared,prepared.steps().getFirst(),mapping()).state()).isEqualTo(State.FAILED);verify(f.gateway,never()).write(any(),any(),any());
    }
    @Test void approvalPendingAndUnsupportedOptionModesCannotBypassTheWriteGuard(){
        var before=input();var prepared=prepare(before,stock(before,3));f.base.path("originProduct").asObject().put("statusType","WAIT");
        assertThat(f.writer.execute(1L,prepared,prepared.steps().getFirst(),mapping()).state()).isEqualTo(State.FAILED);assertThatThrownBy(()->prepare(input(),stock(input(),3))).isInstanceOf(InputValidationFailure.class);
        f.base.path("originProduct").asObject().put("statusType","SALE");var info=f.base.path("originProduct").path("detailAttribute").path("optionInfo").asObject();info.remove("optionCombinations");info.putArray("optionSimpleNames").add("size");
        assertThatThrownBy(()->NaverOptionStockUpdates.requireGeneralUpdateSafe(f.base)).isInstanceOf(InputValidationFailure.class);verify(f.gateway,never()).write(any(),any(),any());
    }
    @Test void priceOnlyAndOptionStructureEditsCannotCopyUnselectedStock(){
        var before=input();var a=before.options().getFirst();var rows=new ArrayList<>(before.options());rows.set(0,new NaverEditor.Option(a.id(),a.values(),200L,a.stockQuantity(),a.sellerManagerCode(),a.usable()));
        var priceOnly=new NaverEditor.Input(before.fields(),before.optionMode(),before.optionNames(),rows,before.images(),before.description());assertThatThrownBy(()->prepare(before,priceOnly)).isInstanceOf(InputValidationFailure.class);
        rows.set(0,new NaverEditor.Option(a.id(),List.of("OTHER"),a.price(),3L,a.sellerManagerCode(),a.usable()));var structure=new NaverEditor.Input(before.fields(),before.optionMode(),before.optionNames(),rows,before.images(),before.description());assertThatThrownBy(()->prepare(before,structure)).isInstanceOf(InputValidationFailure.class);verify(f.gateway,never()).write(any(),any(),any());
    }
    @Test void unreflectedSelectedStockRemainsPending(){var before=input();var prepared=prepare(before,stock(before,3));var step=prepared.steps().getFirst();var result=f.writer.reconcile(1L,prepared,step,new Result(State.ACCEPTED,mapping(),"ACCEPTED",null,Instant.now()));assertThat(result.state()).isEqualTo(State.ACCEPTED);}
    @Test void legacyGeneralPutIsBlockedButExistingResultCanStillBeRead(){var before=input();var desired=f.field(before,"originProduct.name","changed");var d=NaverDraftAdapter.document("1",0L,before);var expected=f.json.createObjectNode().put(NaverEditPatch.PREFIX+"fields.originProduct.name","changed");var step=new Step("legacy",Type.PRODUCT,null,"PUT","/v2/products/origin-products/123","",f.json.writeValueAsString(f.base),"{}",f.json.writeValueAsString(expected));var prepared=new Prepared("naver-account",mapping(),List.of(step),List.of(),Instant.now(),List.of("SKU"),new EditIntent(d,NaverEditPatch.diff(before,desired)),"NAVER");assertThat(f.writer.execute(1L,prepared,step,mapping()).state()).isEqualTo(State.FAILED);f.base.path("originProduct").asObject().put("name","changed");assertThat(f.writer.reconcile(1L,prepared,step,new Result(State.ACCEPTED,mapping(),"ACCEPTED",null,Instant.now())).state()).isEqualTo(State.CONFIRMED);verify(f.gateway,never()).write(any(),any(),any());}
}
