package cc.ataglace.molebutter.marketplace.api;
import java.util.List;
public interface CoupangCatalog {
    default ProductPage products(Long actor,int maxPerPage,String nextToken){return products(actor,maxPerPage,nextToken,ProductSearch.empty());}
    ProductPage products(Long actor,int maxPerPage,String nextToken,ProductSearch search);
    default ProductPage products(Long actor,int size,String token,ProductSearch search,String requestId){return products(actor,size,token,search);}
    default ProductDetail product(Long actor,String id,String requestId){return product(actor,id);}
    record ProductSearch(String sellerProductId,String sellerProductName,String status,String createdAt){
        public static ProductSearch empty(){return new ProductSearch(null,null,null,null);}
    }
    record Field(String name,String value) {}
    record Attribute(String name,String value,String exposed) {}
    record Image(Integer order,String type,String url) {}
    record Content(String type,String detailType,String content) {}
    record Notice(String category,String name,String content) {}
    record Certification(String type,String code,List<Image> attachments) {}
    ProductDetail product(Long actor, String sellerProductId);
    record ProductDetail(Product product, String displayProductName, String generalProductName,
                         String productGroup, String displayCategoryCode, String categoryId,
                         String saleStartedAt, String saleEndedAt, List<ProductOption> items,List<Field> delivery,List<Field> settings) {
        public ProductDetail(Product product,String displayProductName,String generalProductName,String productGroup,String displayCategoryCode,String categoryId,String saleStartedAt,String saleEndedAt,List<ProductOption> items){this(product,displayProductName,generalProductName,productGroup,displayCategoryCode,categoryId,saleStartedAt,saleEndedAt,items,List.of(),List.of());}
        public ProductDetail withItems(List<ProductOption> next){return new ProductDetail(product,displayProductName,generalProductName,productGroup,displayCategoryCode,categoryId,saleStartedAt,saleEndedAt,next,delivery,settings);}
        public ProductDetail { items=List.copyOf(items);delivery=List.copyOf(delivery);settings=List.copyOf(settings); }
    }
    record CurrentInventory(String sellerItemId, Long salePrice, Long amountInStock, Boolean onSale) {}
    record ProductOption(String sellerProductItemId, String vendorItemId, String itemName,
                         CurrentInventory current,String currentError,List<Attribute> attributes,List<Image> images,List<Content> contents,List<Notice> notices,List<Field> settings,List<Certification> certifications) {
        public ProductOption { attributes=List.copyOf(attributes);images=List.copyOf(images);contents=List.copyOf(contents);notices=List.copyOf(notices);settings=List.copyOf(settings);certifications=List.copyOf(certifications); }
        public ProductOption(String id,String vendorId,String name,CurrentInventory current,String error){this(id,vendorId,name,current,error,List.of(),List.of(),List.of(),List.of(),List.of(),List.of());}
        public ProductOption withCurrent(CurrentInventory next,String error){return new ProductOption(sellerProductItemId,vendorItemId,itemName,next,error,attributes,images,contents,notices,settings,certifications);}
        public ProductOption(String sellerProductItemId,String vendorItemId,String itemName){this(sellerProductItemId,vendorItemId,itemName,null,null);}
    }
    record Product(String sellerProductId, String sellerProductName, String brand, String statusName,
                   String productId, String createdAt) {}
    record ProductPage(List<Product> items, String nextToken, boolean hasNext) {
        public ProductPage { items=List.copyOf(items); }
    }
}
