package cc.ataglace.molebutter.coupang.internal.client;

import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.CoupangCatalog;
@Service
final class DefaultCoupangCatalog implements cc.ataglace.molebutter.coupang.internal.RecentProductCandidates, CoupangCatalog, cc.ataglace.molebutter.coupang.api.CoupangEditor, cc.ataglace.molebutter.coupang.api.CoupangRequests {
    private final BusinessAccess access;
    private final CoupangProductClient client;
    public DefaultCoupangCatalog(BusinessAccess access,CoupangProductClient client){this.access=access;this.client=client;}
    public Prediction predict(Long actor,String name,String brand,String sku){access.productActor(actor,true);return client.operation(actor,null,()->client.predict(name,brand,sku));}
    public boolean validCategory(Long actor,String code){access.productActor(actor,true);return client.operation(actor,null,()->client.validCategory(code));}
    public EditorDocument edit(Long actor,String id){return edit(actor,id,null);}
    public EditorDocument edit(Long actor,String id,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.editor(id));}
    public CategoryRules category(Long actor,String code){return category(actor,code,null);}
    public CategoryRules cachedCategory(Long actor,String code){access.productActor(actor,true);return client.cachedCategory(code);}
    public CategoryRules category(Long actor,String code,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.category(code));}
    public java.util.List<CategoryChoice> categories(Long actor,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,client::categories);}
    public ProductDetail product(Long actor,String id){return product(actor,id,null);}
    public ProductDetail product(Long actor,String id,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.product(id));}
    public cc.ataglace.molebutter.coupang.internal.RecentProductCandidates.Page candidates(Long actor,String date,String token) {
        access.productActor(actor,true);return client.operation(actor,null,()->client.candidates(date,token));
    }
    public ProductPage products(Long actor,int size,String token,ProductSearch search){return products(actor,size,token,search,null);}
    public ProductPage products(Long actor,int size,String token,ProductSearch search,String requestId){access.productActor(actor,true);return client.operation(actor,requestId,()->client.products(size,token,search));}
    public void cancel(Long actor,String requestId){access.productActor(actor,true);client.cancel(actor,requestId);}
}
