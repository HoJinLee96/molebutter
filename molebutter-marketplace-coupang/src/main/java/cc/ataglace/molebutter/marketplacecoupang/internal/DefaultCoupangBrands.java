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
        if(id==null||id.isBlank()||id.length()>100||id.indexOf(0)>=0||name==null||name.isBlank()||name.length()>200)throw new InputValidationFailure("브랜드를 검색해서 선택해 주세요.");
        synchronized(seen){var b=seen.get(actor+":"+id);if(b!=null&&b.expires().isAfter(client.now())&&Objects.equals(b.name(),name))return;}
        // A restart or cache expiry must not invalidate a saved brand. Recheck the
        // exact ID/name with the provider; never trust the browser's saved label.
        for(int page=1;page<=10;page++){
            var result=search(actor,name,page,null);
            if(result!=null&&result.items().stream().anyMatch(b->id.equals(b.brandId())&&name.equals(b.brandName())))return;
            if(result==null||!result.hasNext())break;
        }
        throw new InputValidationFailure("저장된 브랜드 ID와 이름을 쿠팡에서 확인하지 못했습니다. 브랜드를 다시 조회해 주세요.");
    }
}
