package cc.ataglace.molebutter.marketplacelotteon.internal.product;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LotteonDeliveryPolicyTest {
    final LotteonCatalog catalog=mock(LotteonCatalog.class);
    LotteonInput input(Map<String,String> intent){return new LotteonInput(new PartnerEditor.Input(Map.of("dvCstPolNo","POLICY","lrtrNo",""),List.of(),List.of(),"","",List.of(),List.of()),List.of(),Map.of(),null,Map.of(),intent);}
    void policy(Map<String,Object> values){when(catalog.metadata(1L,"shipping-policies","",0)).thenReturn(List.of(new LotteonProducts.Choice("POLICY","무료 정책","",true,values)));}
    @Test void fractionalFormatAndExplicitZeroAreComparedWithoutRounding(){policy(Map.of("dvCstDvsCd","B","jejuAdtnDvCst","0.00","inrmAdtnDvCst","5000.00","rcst","4000.00"));LotteonDeliveryPolicy.verify(1L,catalog,input(Map.of("chargeType","FREE","jejuCharge","0","remoteCharge","5000","returnCharge","4000")));assertThatThrownBy(()->LotteonDeliveryPolicy.verify(1L,catalog,input(Map.of("jejuCharge","1000")))).hasMessageContaining("다릅니다");}
    @Test void emptyPolicyListAndMissingAmountsCannotPass(){when(catalog.metadata(1L,"shipping-policies","",0)).thenReturn(List.of());assertThatThrownBy(()->LotteonDeliveryPolicy.verify(1L,catalog,input(Map.of("jejuCharge","0")))).hasMessageContaining("정책");policy(Map.of());assertThatThrownBy(()->LotteonDeliveryPolicy.verify(1L,catalog,input(Map.of("jejuCharge","0")))).hasMessageContaining("다릅니다");}
    @Test void individualPolicyAndUnchangedLegacyValuesNeedNoCommonComparison(){LotteonDeliveryPolicy.verify(1L,catalog,input(Map.of()));LotteonDeliveryPolicy.verify(1L,catalog,input(null));verifyNoInteractions(catalog);}
    @Test void separateInitialFeeIsNeverInventedFromReturnOrExchangePolicy(){policy(Map.of("rcst",3000));assertThatThrownBy(()->LotteonDeliveryPolicy.verify(1L,catalog,input(Map.of("initialReturnCharge","3000")))).hasMessageContaining("별도 확인");}
    @Test void feeIntentSurvivesInternalRoundtripButDoesNotBecomeProductFields(){var i=input(Map.of("jejuCharge","3000"));var json=new ObjectMapper();var next=LotteonInput.structure(json.readValue(json.writeValueAsString(i),LotteonInput.class));assertThat(next.deliveryIntent()).containsEntry("jejuCharge","3000");assertThat(next.product().fields()).doesNotContainKey("jejuCharge");assertThat(LotteonInput.parts(next)).containsKey("deliveryIntent");}
}
