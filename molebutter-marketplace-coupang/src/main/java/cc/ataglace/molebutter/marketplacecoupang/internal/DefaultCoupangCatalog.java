package cc.ataglace.molebutter.marketplacecoupang.internal;

import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.CoupangCatalog;
@Service
final class DefaultCoupangCatalog implements CoupangCatalog, cc.ataglace.molebutter.marketplace.api.CoupangEditor, cc.ataglace.molebutter.marketplace.api.CoupangRequests {
    private final BusinessAccess access;
    private final CoupangProductClient client;
    public DefaultCoupangCatalog(BusinessAccess access,CoupangProductClient client){this.access=access;this.client=client;}
    public EditorDocument edit(Long actor,String id){return edit(actor,id,null);}
    public EditorDocument edit(Long actor,String id,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.editor(id));}
    public CategoryRules category(Long actor,String code){return category(actor,code,null);}
    public CategoryRules category(Long actor,String code,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.category(code));}
    public ProductDetail product(Long actor,String id){return product(actor,id,null);}
    public ProductDetail product(Long actor,String id,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.product(id));}
    public ProductPage products(Long actor,int size,String token,ProductSearch search){return products(actor,size,token,search,null);}
    public ProductPage products(Long actor,int size,String token,ProductSearch search,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.products(size,token,search));}
    public void cancel(Long actor,String requestId){access.productActor(actor,true);client.cancel(actor,requestId);}
}
