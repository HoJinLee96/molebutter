package cc.ataglace.molebutter.infra.product;
import java.util.List;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
public interface ProductSourceGateway {
    String validateUrl(Mall mall,String url);
    List<SourceOption> options(Mall mall,String mallProductId,String url);
    default SourceDetails inspect(Mall mall,String productId,String url){return new SourceDetails("","","",options(mall,productId,url));}
}
