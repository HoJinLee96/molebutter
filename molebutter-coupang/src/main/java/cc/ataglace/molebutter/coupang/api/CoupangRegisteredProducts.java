package cc.ataglace.molebutter.coupang.api;
import java.util.List;
import cc.ataglace.molebutter.common.api.PageResponse;

public interface CoupangRegisteredProducts {
    record Row(CoupangCatalog.Product product, long version, String lastSuccessAt, String lastAttemptAt,
               String error, boolean deleted, boolean deletionCandidate, boolean detailCollected,
               CoupangCatalog.ProductOption option, Integer outboundShippingDays, Recommendation recommendation) {
        public Row(CoupangCatalog.Product product,long version,String success,String attempt,String error,boolean deleted,boolean candidate,boolean collected,CoupangCatalog.ProductOption option,Integer days){this(product,version,success,attempt,error,deleted,candidate,collected,option,days,null);}
        public Row(CoupangCatalog.Product product,long version,String success,String attempt,String error,boolean deleted,boolean candidate,boolean collected){this(product,version,success,attempt,error,deleted,candidate,collected,null,null);}
    }
    record Search(String id,String brand,String name,String sku,Long stock,String stockComparison,
                  String saleStatus,Integer shippingDays,String shippingComparison,String group,String sort,String reason) {
        public Search(String id,String brand,String name,String sku,Long stock,String stockComparison,String saleStatus,Integer shippingDays,String shippingComparison,String group,String sort){this(id,brand,name,sku,stock,stockComparison,saleStatus,shippingDays,shippingComparison,group,sort,"ALL");}
        public Search(String id,String brand,String name,String sku,Long stock,String stockComparison,
                      String saleStatus,Integer shippingDays,String shippingComparison,String group) {
            this(id,brand,name,sku,stock,stockComparison,saleStatus,shippingDays,shippingComparison,group,"CREATED_DESC");
        }
        public Search(String id,String brand,String name,String sku,Long stock,String stockComparison,
                      String saleStatus,Integer shippingDays,String shippingComparison) {
            this(id,brand,name,sku,stock,stockComparison,saleStatus,shippingDays,shippingComparison,"ALL");
        }
    }
    record Recommendation(List<String> reasons,String internalProductId,Long suggestedPrice,
                          java.math.BigDecimal profit,java.math.BigDecimal marginPercent,
                          List<CoupangRecommendationSources.Stock> stocks,int stockOptionCount,
                          boolean previousPrice,String priceCheckedAt,boolean suggestedLoss,StockProposal stockProposal,
                          boolean previousAvailability,String availabilityCheckedAt,String availabilityReason) {
        public Recommendation(List<String> reasons,String id,Long price,java.math.BigDecimal profit,java.math.BigDecimal margin,List<CoupangRecommendationSources.Stock> stocks,int count,boolean previous,String at,boolean loss,StockProposal proposal,boolean previousAvailability,String availabilityAt){this(reasons,id,price,profit,margin,stocks,count,previous,at,loss,proposal,previousAvailability,availabilityAt,null);}
        public Recommendation(List<String> reasons,String id,Long price,java.math.BigDecimal profit,java.math.BigDecimal margin,List<CoupangRecommendationSources.Stock> stocks,int count,boolean previous,String at,boolean loss){this(reasons,id,price,profit,margin,stocks,count,previous,at,loss,null,false,null);}
    }
    record StockProposal(String source,Long quantity,boolean selectionRequired) {}
    record RecommendationCounts(long priceHigh,long negativeMargin,long restock,long markSoldOut,long ownedStockMismatch) {
        public RecommendationCounts(long priceHigh,long negativeMargin,long restock){this(priceHigh,negativeMargin,restock,0,0);}
    }
    record Counts(long all,long selling,long soldOut,long stopped,long deleted,long recommended,RecommendationCounts recommendationReasons) {
        public Counts(long all,long selling,long soldOut,long stopped,long deleted,long recommended){this(all,selling,soldOut,stopped,deleted,recommended,new RecommendationCounts(0,0,0));}
        public Counts(long all,long selling,long soldOut,long stopped,long deleted){this(all,selling,soldOut,stopped,deleted,0);}
    }
    record Listing(List<Row> items,int page,int totalPages,long totalElements,long productCount,Counts counts) {}
    record Conflict(String key, CoupangEditing.Change change, Object baseline, Object current, Object desired,
                    String state) {}
    record Detail(Row state, CoupangCatalog.ProductDetail detail, String workingCopyId, Long revision,
                  List<Conflict> conflicts, boolean blocked) {}
    record Event(String id, String kind, String subject, String path, String beforeJson, String afterJson,
                 Long actor, String jobId, String executionId, String createdAt) {}
    record OptionEvent(String id,String kind,Long before,Long after,Long actor,String executionId,String createdAt) {}
    record Collection(String id, String mode, String status, String phase, boolean listComplete,
                      long discovered, long completed, long failed, String error, String updatedAt, RecentCollection recent) {
        public Collection(String id,String mode,String status,String phase,boolean listComplete,long discovered,long completed,long failed,String error,String updatedAt) {
            this(id,mode,status,phase,listComplete,discovered,completed,failed,error,updatedAt,null);
        }
    }
    record RecentCollection(String createdDateFrom,String createdDateTo,int maxProducts,String cutoffAt,
                            String currentDate,long apiPages,long candidates,long selected,boolean selectionComplete) {}
    record Collect(List<String> sellerProductIds,String mode,String createdDateFrom,String createdDateTo,Integer maxProducts) {
        public Collect(List<String> sellerProductIds){this(sellerProductIds,null,null,null,null);}
    }
    record Resolve(long observedVersion, long revision, String key, String choice) {}
    record DeleteConfirmation(long observedVersion, boolean deleted) {}
    record Action(String type, String vendorItemId, long observedVersion) {}
    Listing list(Long actor,Search search,boolean deleted,int page,int size);
    String optionProductUrl(Long actor,String vendorItemId);
    List<String> brands(Long actor);
    Detail get(Long actor, String productId);
    PageResponse<Event> history(Long actor, String productId, int page, int size);
    PageResponse<OptionEvent> optionHistory(Long actor,String productId,String vendorItemId,String kind,int page,int size);
    Collection collect(Long actor, Collect input);
    Collection collection(Long actor, String id);
    Collection latestCollection(Long actor);
    Collection cancel(Long actor, String id);
    Collection resume(Long actor, String id);
    Detail resolve(Long actor, String productId, Resolve input);
    Detail confirmDeletion(Long actor, String productId, DeleteConfirmation input);
    CoupangSubmissions.Preview prepareAction(Long actor, String productId, Action input);
}
