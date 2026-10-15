package cc.ataglace.molebutter.marketplace.api;
import java.util.List;
import java.util.Map;
public interface LotteonProducts {
 record Query(String productId,String name,String status,String from,String to,int page,int size) {}
 record Choice(String id,String name,String parent,boolean leaf,Map<String,Object> data) {}
 record ChoicePage(List<Choice> items,int page,boolean hasNext) {}
 record Detail(PartnerEditor.Observation product,LotteonInput input,boolean supported,String approval) {}
 PartnerEditor.ProductPage list(Long actor,Query query);
 Detail get(Long actor,String productId);
 PartnerEditor.Schema schema(Long actor);
 List<Choice> metadata(Long actor,String kind,String filter,int page);
 ChoicePage metadataPage(Long actor,String kind,String filter,int page);
 default ChoicePage metadataPage(Long actor,String kind,String filter,String parent,int page){return metadataPage(actor,kind,filter,page);}
}
