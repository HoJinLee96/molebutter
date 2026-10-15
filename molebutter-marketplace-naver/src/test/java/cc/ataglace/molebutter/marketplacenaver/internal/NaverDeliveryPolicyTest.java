package cc.ataglace.molebutter.marketplacenaver.internal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.marketplace.api.NaverEditor;
import static org.assertj.core.api.Assertions.*;

class NaverDeliveryPolicyTest {
    final ObjectMapper json=new ObjectMapper();final String P="originProduct.deliveryInfo.",A=P+"deliveryFee.deliveryFeeByArea.";
    NaverEditor.Input input(String id){var f=new LinkedHashMap<String,Object>();f.put(P+"deliveryBundleGroupUsable",true);if(id!=null)f.put(P+"deliveryBundleGroupId",id);f.put(A+"deliveryAreaType","AREA_3");f.put(A+"area2extraFee",3000);f.put(A+"area3extraFee",5000);return new NaverEditor.Input(f,"NONE",List.of(),List.of(),List.of(),"");}
    JsonNode group(String area,int jeju,int remote){return json.valueToTree(Map.of("deliveryBundleGroup",Map.of("id",123,"usable",true,"deliveryFeeByArea",Map.of("deliveryAreaType",area,"area2extraFee",jeju,"area3extraFee",remote))));}
    @Test void threeZonePolicyMustMatchBothAmounts(){NaverDeliveryPolicy.verify(input("123"),q->{assertThat(q).containsEntry("groupId","123");return group("AREA_3",3000,5000);});assertThatThrownBy(()->NaverDeliveryPolicy.verify(input("123"),q->group("AREA_3",3000,4000))).hasMessageContaining("제주 외");}
    @Test void twoZonePolicyCannotHideDifferentJejuAndIslandAmounts(){assertThatThrownBy(()->NaverDeliveryPolicy.verify(input("123"),q->group("AREA_2",3000,5000))).hasMessageContaining("제주 외");}
    @Test void selectedGroupAndFreshReadAreRequired(){assertThatThrownBy(()->NaverDeliveryPolicy.verify(input(null),q->{throw new AssertionError("must not query without ID");})).hasMessageContaining("조회·선택");assertThatThrownBy(()->NaverDeliveryPolicy.verify(input("123"),q->null)).hasMessageContaining("확인하지 못했습니다");}
    @Test void explicitlySelectedGroupIsVerifiedEvenWithoutRegionalFees(){var i=input("123");i.fields().remove(A+"area2extraFee");i.fields().remove(A+"area3extraFee");assertThatThrownBy(()->NaverDeliveryPolicy.verify(i,q->json.valueToTree(Map.of("id",123,"usable",false)))).hasMessageContaining("사용 가능");}
    @Test void unbundledProductDoesNotQuerySharedPolicy(){var i=input(null);i.fields().put(P+"deliveryBundleGroupUsable",false);NaverDeliveryPolicy.verify(i,q->{throw new AssertionError("unbundled product");});}
}
