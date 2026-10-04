package cc.ataglace.molebutter.infra.product;
import java.util.List;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
public interface SupplierProductGateway {
    String validateUrl(ProcurementMall mall,String url);
    List<SourceOption> options(ProcurementMall mall,String mallProductId,String url);
    default SourceDetails inspect(ProcurementMall mall,String productId,String url){return new SourceDetails("","","",options(mall,productId,url));}
}
