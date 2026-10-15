package cc.ataglace.molebutter.marketplace.api;
import java.util.*;
public interface ElevenstProducts {
 record Query(String productId,String name,String status,String dateType,String from,String to,int page,int size) {}
 record Choice(String id,String name,String parentId,boolean leaf) {}
 record Address(String code,String name,String address,String detail,String zip,String phone) {}
 PartnerEditor.ProductPage list(Long actor,Query query);
 PartnerEditor.Observation get(Long actor,String productId);
 List<Choice> categories(Long actor);
 List<Address> addresses(Long actor,String kind);
 List<Choice> shippingTemplates(Long actor);
 PartnerEditor.Schema schema(Long actor);
 record BrandPage(List<Choice> items,int page,boolean hasNext,boolean available,String warning) {}
 BrandPage brands(Long actor,String name,int page);
 List<Choice> deliveryCompanies(Long actor);
 List<Choice> origins(Long actor);
}
