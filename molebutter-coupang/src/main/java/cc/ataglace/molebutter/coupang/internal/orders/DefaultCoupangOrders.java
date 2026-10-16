package cc.ataglace.molebutter.coupang.internal.orders;

import cc.ataglace.molebutter.coupang.api.CoupangOrderGateway;
import java.util.Set;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.CoupangOrders;

@Service
public final class DefaultCoupangOrders implements CoupangOrders {
    private final BusinessAccess access;private final CoupangOrderStore store;private final CoupangOrderGateway client;
    public DefaultCoupangOrders(BusinessAccess access,CoupangOrderStore store,CoupangOrderGateway client){this.access=access;this.store=store;this.client=client;}
    @Override public OrderPage orders(Long actor,Search search,int page,int size){
        access.productActor(actor,true);if(page<0||size<1||size>100)throw new InputValidationFailure("조회 페이지를 확인해 주세요.");
        if(search!=null){
            if(search.query()!=null&&search.query().length()>200)throw new InputValidationFailure("검색어는 200자 이내로 입력해 주세요.");
            var from=DefaultCoupangOrderCollections.parse(search.dateFrom(),null);var to=DefaultCoupangOrderCollections.parse(search.dateTo(),null);if(from!=null&&to!=null&&to.isBefore(from))throw new InputValidationFailure("조회 날짜를 확인해 주세요.");
            if(search.dateBasis()!=null&&!search.dateBasis().isBlank()&&!Set.of("ORDERED","PAID","CLAIM").contains(search.dateBasis()))throw new InputValidationFailure("조회 날짜 기준을 확인해 주세요.");
        }
        return store.orders(client.accountKey(),search,page,size);
    }
    @Override public Detail detail(Long actor,String orderId){access.productActor(actor,true);String account=client.accountKey();String reason=store.detailReason(account,orderId);
        if("ORDER_UNAVAILABLE".equals(reason)){access.productActor(actor,true);return store.storedDetail(account,orderId,reason);}
        Detail detail;try{detail=client.detail(orderId);}catch(cc.ataglace.molebutter.coupang.api.CoupangFailure failure){access.productActor(actor,true);if(failure.kind()==cc.ataglace.molebutter.coupang.api.CoupangFailure.Kind.ORDER_UNAVAILABLE&&store.collectedAt(account,orderId)!=null){store.markUnavailable(account,orderId);return store.storedDetail(account,orderId,failure.kind().name());}throw failure;}access.productActor(actor,true);return new Detail(detail.orderId(),detail.shipments(),detail.items().stream().map(row->store.enrich(client.accountKey(),row)).toList(),java.time.Instant.now().toString(),store.collectedAt(client.accountKey(),orderId));}
}
