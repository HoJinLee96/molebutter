package cc.ataglace.molebutter.coupang.api;
import java.math.BigDecimal;
public interface CoupangPricing {
    record Settings(long revision, long shippingCost, BigDecimal commissionPercent, BigDecimal marginPercent, Long updatedBy, String updatedAt) {}
    record Save(Long revision, Long shippingCost, BigDecimal commissionPercent, BigDecimal marginPercent) {}
    record Calculation(long calculatedPrice, long suggestedPrice, BigDecimal profit, BigDecimal marginPercent,
        BigDecimal purchaseSupply, BigDecimal purchaseVat, BigDecimal shippingSupply, BigDecimal shippingVat,
        BigDecimal saleSupply, BigDecimal saleVat, BigDecimal commission, BigDecimal commissionVat, BigDecimal payableVat) {}
    Settings get(Long actor);
    Settings save(Long actor, Save input);
    Calculation calculate(Settings settings, long purchaseCost, Long salePrice);
}
