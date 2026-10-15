package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplacecoupang.internal.DraftCoupangProjection.delivery;
final class CoupangDeliveryPolicy {
    static void verify(CoupangProductClient client,Document d,MarketConfig m){
        var values=d.delivery();if(MarketplaceDocuments.blank(values.jejuCharge())&&MarketplaceDocuments.blank(values.remoteCharge()))return;
        var resolved=delivery(d,m);if(!"Y".equals(resolved.get("remoteAreaDeliverable")))return;
        var code=resolved.get("outboundShippingPlaceCode");var page=client.rawOutboundPlace(code);var place=page.items().stream().filter(p->p.usable()&&p.code().equals(code)).findFirst().orElseThrow(()->new MarketplaceSettingsFailure("delivery","도서산간 배송을 확인할 출고지를 선택해 주세요."));
        var policies=place.remoteInfos().stream().filter(r->r.usable()&&r.carrier().equals(resolved.get("deliveryCompanyCode"))).toList();if(policies.size()!=1)throw new MarketplaceSettingsFailure("delivery","출고지의 택배사별 도서산간 배송비를 확인할 수 없습니다. 출고지를 다시 선택해 주세요.");
        MarketplaceCommonSettings.equalPolicy("제주 추가 배송비",values.jejuCharge(),policies.getFirst().jeju());MarketplaceCommonSettings.equalPolicy("제주 외 도서산간 추가 배송비",values.remoteCharge(),policies.getFirst().remote());
    }
}
