package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;


public interface OfficialBrandStoreService {
    ChannelPreview preview(Long actor,String url);
    Store register(Long actor,StoreInput input);
}
