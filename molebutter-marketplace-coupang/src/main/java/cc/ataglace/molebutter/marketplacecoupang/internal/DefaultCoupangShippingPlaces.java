package cc.ataglace.molebutter.marketplacecoupang.internal;

import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces;
@Service
final class DefaultCoupangShippingPlaces implements CoupangShippingPlaces {
    private final BusinessAccess access; private final CoupangProductClient client;
    public DefaultCoupangShippingPlaces(BusinessAccess access,CoupangProductClient client){this.access=access;this.client=client;}
    public Page outbound(Long actor,String code,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.outboundPlace(code));}
    public Page list(Long actor,boolean returns,int page,String requestId){
        access.productActor(actor,true);
        return client.operation(actor,requestId,()->client.shippingPlaces(returns,page));
    }
}
