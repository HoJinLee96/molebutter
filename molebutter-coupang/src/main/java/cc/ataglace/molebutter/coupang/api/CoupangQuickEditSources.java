package cc.ataglace.molebutter.coupang.api;
import java.util.List;
/** App-owned, DB-only procurement adapter. No permanent sales/procurement binding. */
public interface CoupangQuickEditSources {
    record Stock(String id,String label,long quantity,boolean previous,String checkedAt,String state) {
        public Stock(String id,String label,long quantity,boolean previous,String checkedAt){this(id,label,quantity,previous,checkedAt,null);}
        public Stock(String id,String label,long quantity){this(id,label,quantity,false,null);}
    }
    record StockOption(String id,String label,Long quantity,String state,boolean previous,String checkedAt) {}
    record OwnedOption(String color,String size,long quantity) {}
    record Source(String productId,String productCode,Long purchaseCost,String checkedAt,boolean previous,List<Stock> stocks,String fingerprint,int stockOptionCount,List<StockOption> stockOptions,String availability,boolean previousAvailability,String availabilityCheckedAt,
                  Long ownedQuantity,List<OwnedOption> ownedOptions,int couponOptionCount,String availabilityReason,CoupangRegisteredProducts.StockProposal stockProposal) {
        public Source(String id,String code,Long cost,String at,boolean previous,List<Stock> stocks,String fingerprint,int count,List<StockOption> options,String availability,boolean previousAvailability,String availabilityAt,Long owned,List<OwnedOption> ownedOptions,int couponCount){this(id,code,cost,at,previous,stocks,fingerprint,count,options,availability,previousAvailability,availabilityAt,owned,ownedOptions,couponCount,null,null);}
        public Source(String id,String code,Long cost,String at,boolean previous,List<Stock> stocks,String fingerprint,int count,List<StockOption> options){this(id,code,cost,at,previous,stocks,fingerprint,count,options,"UNKNOWN",false,null,null,List.of(),0);}

        public Source(String productId,String productCode,Long purchaseCost,String checkedAt,boolean previous,List<Stock> stocks,String fingerprint,int stockOptionCount){this(productId,productCode,purchaseCost,checkedAt,previous,stocks,fingerprint,stockOptionCount,stocks.stream().map(s->new StockOption(s.id(),s.label(),s.quantity(),s.state(),s.previous(),s.checkedAt())).toList());}
        public Source(String productId,String productCode,Long purchaseCost,String checkedAt,boolean previous,List<Stock> stocks,String fingerprint){this(productId,productCode,purchaseCost,checkedAt,previous,stocks,fingerprint,stocks.size());}
    }
    Source get(Long actor,String productId);
}
