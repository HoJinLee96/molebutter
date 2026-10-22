package cc.ataglace.molebutter.coupang.api;
import java.util.*;
/** App-owned batch read of unique active products and verified selected-listing evidence. */
public interface CoupangRecommendationSources {
    record Stock(String id,String label,long quantity,String state) {}
    record Source(String productId,Long purchaseCost,boolean previousPrice,String priceCheckedAt,
                  List<Stock> stocks,int stockOptionCount,String availability,boolean previousAvailability,String availabilityCheckedAt,
                  Long ownedQuantity,int ownedOptionCount,String availabilityReason) {
        public Source(String id,Long cost,boolean previous,String at,List<Stock> stocks,int count,String availability,boolean previousAvailability,String availabilityAt,Long owned,int ownedCount){this(id,cost,previous,at,stocks,count,availability,previousAvailability,availabilityAt,owned,ownedCount,null);}
        public Source(String productId,Long cost,boolean previous,String at,List<Stock> stocks,int count){this(productId,cost,previous,at,stocks,count,stocks.isEmpty()?"UNKNOWN":"AVAILABLE",false,at,null,0);}
    }
    static CoupangRegisteredProducts.StockProposal stockProposal(String availability,Long owned,int ownedOptions,int coupangOptions,List<Stock> stocks,int optionCount) {
        if(Set.of("UNAVAILABLE","SELECTED_MISSING").contains(Objects.toString(availability,""))&&owned!=null)
            return owned==0?new CoupangRegisteredProducts.StockProposal("ZERO",0L,false):
                new CoupangRegisteredProducts.StockProposal("OWNED",ownedOptions==1&&coupangOptions==1?owned:null,ownedOptions!=1||coupangOptions!=1);
        if("AVAILABLE".equals(availability)&&!stocks.isEmpty())return new CoupangRegisteredProducts.StockProposal("SUPPLIER",optionCount==1&&stocks.size()==1?stocks.getFirst().quantity():null,optionCount!=1||stocks.size()!=1);
        return null;
    }
    Map<String,Source> byCodes(Long actor,Set<String> codes);
}
