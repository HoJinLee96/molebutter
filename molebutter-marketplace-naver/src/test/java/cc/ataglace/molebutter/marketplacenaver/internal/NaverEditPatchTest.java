package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import static org.assertj.core.api.Assertions.*;

class NaverEditPatchTest {
    final ObjectMapper json=new ObjectMapper();
    NaverEditor.Input input(Map<String,Object> fields){return new NaverEditor.Input(fields,"NONE",List.of(),List.of(),List.of(),"");}
    @Test void incompleteDraftKeepsTypedZeroAndDoesNotAcceptWireIdentifiers(){
        var normalized=NaverEditPatch.normalize(input(Map.of("originProduct.salePrice","0","originProduct.stockQuantity","0","smartstoreChannelProduct.naverShoppingRegistration","false")));
        assertThat(normalized.fields()).containsEntry("originProduct.salePrice",0L).containsEntry("originProduct.stockQuantity",0L).containsEntry("smartstoreChannelProduct.naverShoppingRegistration",false);
        assertThatThrownBy(()->NaverEditPatch.normalize(input(Map.of("originProduct.originProductNo",123L)))).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->NaverEditPatch.normalize(input(Map.of("originProduct.detailAttribute.optionInfo.optionCombinations",List.of())))).isInstanceOf(InputValidationFailure.class);
        assertThat(NaverEditPatch.diff(input(Map.of("originProduct.salePrice",100)),input(Map.of("originProduct.salePrice","100")))).isEmpty();
    }
    @Test void clearOptionalNumbersIsDifferentFromZeroAndRoundtripsDraft(){
        var before=input(Map.of("originProduct.detailAttribute.naverShoppingSearchInfo.brandId",1L));var cleared=new LinkedHashMap<String,Object>();cleared.put("originProduct.detailAttribute.naverShoppingSearchInfo.brandId",null);var after=input(cleared);
        var document=NaverDraftAdapter.document("1",0L,before);var changes=NaverEditPatch.diff(before,after);assertThat(changes).hasSize(1);assertThat(NaverDraftAdapter.from(NaverEditPatch.apply(document,changes)).fields()).containsEntry("originProduct.detailAttribute.naverShoppingSearchInfo.brandId",null);
        assertThatThrownBy(()->NaverEditPatch.normalize(input(Map.of("originProduct.stockQuantity","1.5")))).isInstanceOf(InputValidationFailure.class);
    }
    @Test void noticeOnlyContainsSelectedTypeAndYearMonthObjectsRemainReadable(){
        var notice=Map.of("productInfoProvidedNoticeType","BAG","bag",Map.of("type","가방"),"etc",Map.of("itemName","임시 입력"));var normalized=NaverEditPatch.normalize(input(Map.of("originProduct.detailAttribute.productInfoProvidedNotice",notice)));
        var value=json.valueToTree(normalized.fields().get("originProduct.detailAttribute.productInfoProvidedNotice"));assertThat(value.has("bag")).isTrue();assertThat(value.has("etc")).isFalse();
        var month=Map.of("productInfoProvidedNoticeType","SEASON_APPLIANCES","seasonAppliances",Map.of("releaseDate",Map.of("year",2026,"monthValue",10)));
        assertThatCode(()->NaverEditPatch.normalize(input(Map.of("originProduct.detailAttribute.productInfoProvidedNotice",month)))).doesNotThrowAnyException();
    }
    @Test void trustedProjectionDetectsGroupAndPreservesMissingOptionStock(){
        var source=json.readTree("""
          {"groupProduct":{"groupProductNo":99},"originProduct":{"originProductNo":1,"detailAttribute":{"optionInfo":{"optionCombinationGroupNames":{"optionGroupName1":"색상"},"optionCombinations":[{"id":7,"optionName1":"브라운"}]}}},"smartstoreChannelProduct":{"channelProductNo":2}}
          """);
        var projection=NaverEditPatch.editor(source);assertThat(projection.limits().groupProduct()).isTrue();assertThat(projection.input().options().getFirst().stockQuantity()).isNull();assertThat(projection.input().options().getFirst().price()).isNull();
        assertThat(projection.optionIdentities().getFirst().remoteId()).isEqualTo("7");assertThat(NaverEditPatch.editor(source).optionIdentities()).isEqualTo(projection.optionIdentities());
    }
    @Test void optionAndAssetIdsMustBeUuidAndUnuploadedBlobCannotEnterDraft(){
        assertThatThrownBy(()->NaverEditPatch.normalize(new NaverEditor.Input(Map.of(),"COMBINATION",List.of("색상"),List.of(new NaverEditor.Option("remote-7",List.of("브라운"),0L,0L,"",true)),List.of(),""))).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->NaverEditPatch.normalize(new NaverEditor.Input(Map.of(),"NONE",List.of(),List.of(),List.of(new NaverEditor.Image(UUID.randomUUID().toString(),null,"blob:private",true,0)),""))).isInstanceOf(InputValidationFailure.class);
    }
    @Test void rekeyUsesRemoteOptionIdAndIgnoresArrayPosition(){
        var source=json.readTree("""
          {"originProduct":{"originProductNo":1,"detailAttribute":{"optionInfo":{"optionCombinationGroupNames":{"optionGroupName1":"색상"},"optionCombinations":[{"id":8,"optionName1":"흰색","price":0,"stockQuantity":0},{"id":7,"optionName1":"검정","price":0,"stockQuantity":0}]}}},"smartstoreChannelProduct":{"channelProductNo":2}}
          """);
        var view=NaverEditPatch.editor(source);String black=UUID.randomUUID().toString(),white=UUID.randomUUID().toString();var mapping=new MarketplaceWriteGateway.Mapping("n","1",List.of(new MarketplaceWriteGateway.OptionMapping(black,"7",null),new MarketplaceWriteGateway.OptionMapping(white,"8",null)),"2");
        var reference=NaverDraftAdapter.document("1",0L,view.input());var observed=NaverDraftAdapter.from(NaverDraftAdapter.observed(reference,view,mapping));assertThat(observed.options()).extracting(NaverEditor.Option::id).containsExactly(white,black);
    }
    @Test void numericJsonRoundtripPreservesValuesButNotMissingFieldsOrArrayOrder(){
        var original=json.valueToTree(Map.of("rows",List.of(Map.of("price",2000L,"stock",0L),Map.of("price",3000L,"stock",1L))));var stored=json.readTree(json.writeValueAsString(original));
        assertThat(NaverEditPatch.jsonEquivalent(original,stored)).isTrue();assertThat(NaverEditPatch.equivalent(Map.of("value",2000L),Map.of("value",new java.math.BigDecimal("2000.00")))).isTrue();
        assertThat(NaverEditPatch.jsonEquivalent(original,json.readTree("{\"rows\":[{\"price\":3000,\"stock\":1},{\"price\":2000,\"stock\":0}]}"))).isFalse();
        assertThat(NaverEditPatch.jsonEquivalent(original,json.readTree("{\"rows\":[{\"price\":2000},{\"price\":3000,\"stock\":1}]}"))).isFalse();
        assertThat(NaverEditPatch.equivalent(2000L,"2000")).isFalse();assertThat(NaverEditPatch.equivalent(2000L,2001)).isFalse();
    }
}
