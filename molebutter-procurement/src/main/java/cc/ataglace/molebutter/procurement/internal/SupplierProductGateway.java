package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;

import java.util.List;

public interface SupplierProductGateway {
    String validateUrl(ProcurementMall mall,String url);
    List<SourceOption> options(ProcurementMall mall,String mallProductId,String url);
    default SourceDetails inspect(ProcurementMall mall,String productId,String url){return new SourceDetails("","","",options(mall,productId,url));}
}
