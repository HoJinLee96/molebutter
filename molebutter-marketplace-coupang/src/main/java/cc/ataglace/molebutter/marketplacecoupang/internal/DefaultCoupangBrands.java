package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.util.*;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.CoupangBrands;
@Service
final class DefaultCoupangBrands implements CoupangBrands {
    private final BusinessAccess access; private final CoupangProductClient client;
    private final Map<String,Seen> seen=new LinkedHashMap<>();
    private record Seen(String name,java.time.Instant expires) {}
    public DefaultCoupangBrands(BusinessAccess access,CoupangProductClient client){this.access=access;this.client=client;}
    public Page search(Long actor,String name,int page,String requestId){
        access.productActor(actor,true);
        var result=client.operation(actor,requestId,()->client.brands(name,page));
        synchronized(seen){seen.entrySet().removeIf(e->!e.getValue().expires().isAfter(client.now()));for(var b:result.items())seen.put(actor+":"+b.brandId(),new Seen(b.brandName(),client.now().plusSeconds(3600)));while(seen.size()>4096)seen.remove(seen.keySet().iterator().next());}
        return result;
    }
    public void requireSelection(Long actor,String id,String name){
        access.productActor(actor,true);
        synchronized(seen){var b=seen.get(actor+":"+id);if(b==null||!b.expires().isAfter(client.now())||!Objects.equals(b.name(),name))throw new InputValidationFailure("브랜드를 검색해서 선택해 주세요.");}
    }
}
