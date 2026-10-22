package cc.ataglace.molebutter.coupang.internal;
import java.math.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.coupang.api.CoupangPricing;
@Service
public class DefaultCoupangPricing implements CoupangPricing {
    private final JdbcTemplate db; private final BusinessAccess access;
    private static final BigDecimal VAT=new BigDecimal("1.1"), HUNDRED=new BigDecimal("100");
    private static final MathContext MC=new MathContext(30,RoundingMode.HALF_UP);
    public DefaultCoupangPricing(JdbcTemplate db,BusinessAccess access){this.db=db;this.access=access;}
    public Settings get(Long actor){access.productActor(actor,false);return db.queryForObject("SELECT * FROM coupang_pricing_settings WHERE id=1",(r,n)->new Settings(r.getLong("revision"),r.getLong("shipping_cost"),r.getBigDecimal("commission_percent"),r.getBigDecimal("margin_percent"),(Long)r.getObject("updated_by"),r.getTimestamp("updated_at").toInstant().toString()));}
    @Transactional public Settings save(Long actor,Save input){
        access.productActor(actor,true);
        if(input==null||input.revision()==null||input.shippingCost()==null)throw invalid();
        validate(input.shippingCost(),input.commissionPercent(),input.marginPercent());
        if(db.update("UPDATE coupang_pricing_settings SET shipping_cost=?,commission_percent=?,margin_percent=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND revision=?",input.shippingCost(),input.commissionPercent(),input.marginPercent(),actor,input.revision())!=1)
            throw new cc.ataglace.molebutter.common.api.BusinessException(cc.ataglace.molebutter.common.api.ErrorCode.OPTIMISTIC_LOCKING_FAILURE);
        return get(actor);
    }
    private static InputValidationFailure invalid(){return new InputValidationFailure("배송비·수수료율·희망 마진율을 확인해 주세요.");}
    static void validate(long shipping,BigDecimal commission,BigDecimal margin){
        if(shipping<0||shipping>9007199254740991L||commission==null||margin==null||commission.signum()<0||margin.signum()<0||commission.scale()>4||margin.scale()>4||commission.compareTo(HUNDRED)>=0||margin.compareTo(HUNDRED)>=0||denominator(commission,margin).signum()<=0)throw invalid();
    }
    private static BigDecimal denominator(BigDecimal commission,BigDecimal margin){return BigDecimal.ONE.subtract(VAT.multiply(commission.add(margin).divide(HUNDRED)));}
    public Calculation calculate(Settings settings,long purchaseCost,Long salePrice){
        validate(settings.shippingCost(),settings.commissionPercent(),settings.marginPercent());
        if(purchaseCost<0||purchaseCost>9007199254740991L)throw invalid();
        BigDecimal purchase=BigDecimal.valueOf(purchaseCost),shipping=BigDecimal.valueOf(settings.shippingCost());
        long calculated;
        try{calculated=purchase.add(shipping).divide(denominator(settings.commissionPercent(),settings.marginPercent()),0,RoundingMode.HALF_UP).longValueExact();}catch(ArithmeticException failure){throw invalid();}
        long suggested=(calculated/1000)*1000;if(suggested%10000==0)suggested-=1000;
        if(suggested<=0||suggested>9007199254740991L)throw new InputValidationFailure("계산한 제안 가격이 유효하지 않습니다. 사입가와 계산 설정을 확인해 주세요.");
        long selected=salePrice==null?suggested:salePrice;
        if(selected<=0||selected>9007199254740991L||selected%10!=0)throw new InputValidationFailure("판매가격은 0보다 큰 10원 단위로 입력해 주세요.");
        var sale=BigDecimal.valueOf(selected);var ps=purchase.divide(VAT,MC);var ds=shipping.divide(VAT,MC);var ss=sale.divide(VAT,MC);
        var commission=sale.multiply(settings.commissionPercent()).divide(HUNDRED);var cv=commission.multiply(new BigDecimal("0.1"));
        var pv=purchase.subtract(ps);var dv=shipping.subtract(ds);var sv=sale.subtract(ss);var payable=sv.subtract(pv).subtract(dv).subtract(cv);
        // Compute the numerator exactly so true break-even cannot become a negative rounding residue.
        var profit=sale.subtract(VAT.multiply(commission)).subtract(purchase).subtract(shipping).divide(VAT,MC);var margin=profit.divide(sale,MC).multiply(HUNDRED);
        return new Calculation(calculated,suggested,profit,margin,ps,pv,ds,dv,ss,sv,commission,cv,payable);
    }
}
