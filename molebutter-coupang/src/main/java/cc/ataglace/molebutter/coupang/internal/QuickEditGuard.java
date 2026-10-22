package cc.ataglace.molebutter.coupang.internal;
import java.util.Objects;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
@Component
final class QuickEditGuard {
    private final CoupangPricing pricing;private final CoupangQuickEditSources sources;
    QuickEditGuard(CoupangPricing pricing,CoupangQuickEditSources sources){this.pricing=pricing;this.sources=sources;}
    void check(Long actor,CoupangOptionEdits.Basis basis){
        if(basis==null)return;
        if(!Objects.equals(sources.get(actor,basis.productId()).fingerprint(),basis.fingerprint())||basis.pricingRevision()!=null&&pricing.get(actor).revision()!=basis.pricingRevision())
            throw new InputValidationFailure("계산 설정이나 선정 매입처·보유 재고 정보가 변경되었습니다. 입력을 유지하고 다시 확인해 주세요.");
    }
}
