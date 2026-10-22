package cc.ataglace.molebutter.coupang.internal;
import java.math.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.coupang.api.CoupangPricing;
import static org.assertj.core.api.Assertions.*;
class CoupangPricingTest {
    final DefaultCoupangPricing pricing=new DefaultCoupangPricing(null,null);
    CoupangPricing.Settings settings(long shipping,String fee,String margin){return new CoupangPricing.Settings(0,shipping,new BigDecimal(fee),new BigDecimal(margin),null,null);}
    @Test void suppliedExamplesAndActualMarginAfterRounding(){
        var a=pricing.calculate(settings(4000,"10.5","7"),115170,null);
        assertThat(a.calculatedPrice()).isEqualTo(147579);assertThat(a.suggestedPrice()).isEqualTo(147000);
        assertThat(a.marginPercent()).isLessThan(new BigDecimal("7"));
        var b=pricing.calculate(settings(4000,"10.5","7"),206080,null);
        assertThat(b.calculatedPrice()).isEqualTo(260161);assertThat(b.suggestedPrice()).isEqualTo(259000);
        assertThat(b.profit()).isCloseTo(b.saleSupply().subtract(b.commission()).subtract(b.purchaseSupply()).subtract(b.shippingSupply()),org.assertj.core.data.Offset.offset(new BigDecimal("0.00000000000000000000001")));
        assertThat(pricing.calculate(settings(0,"0","0"),150000,null).suggestedPrice()).isEqualTo(149000);
    }
    @Test void manualPriceRecalculatesProfitAndInvalidSettingsCannotDivideOrOverflow(){
        assertThat(pricing.calculate(settings(4000,"10.5","7"),115170,148000L).marginPercent()).isGreaterThan(new BigDecimal("7"));
        for(var input:java.util.List.of(settings(-1,"10.5","7"),settings(4000,"91","0"),settings(4000,"-1","7"),settings(4000,"10.5","-1")))assertThatThrownBy(()->pricing.calculate(input,115170,null)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        assertThatThrownBy(()->pricing.calculate(settings(0,"0","0"),0,null)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        assertThatThrownBy(()->pricing.calculate(settings(4000,"10.5","7"),115170,147001L)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
}
