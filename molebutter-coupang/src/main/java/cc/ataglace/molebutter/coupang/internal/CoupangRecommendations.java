package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.Recommendation;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

@Service
public class CoupangRecommendations {
    private final RegisteredProductStore store;
    private final CoupangPricing pricing;
    private final CoupangRecommendationSources sources;
    public CoupangRecommendations(RegisteredProductStore store,CoupangPricing pricing,CoupangRecommendationSources sources){this.store=store;this.pricing=pricing;this.sources=sources;}
    record Key(String seller,String item) {}
    record Candidate(Key key,String code,Long price,Long stock,boolean onSale) {}
    static String normalize(String code){return Objects.toString(code,"").trim().toUpperCase(Locale.ROOT);}
    Map<Key,Recommendation> account(Long actor,String account) {
        var candidates=store.db.query("""
            SELECT p.seller_product_id,o.seller_product_item_id,o.sku,o.current_price,o.current_stock,o.on_sale
            FROM coupang_registered_product p JOIN coupang_registered_option o ON o.product_id=p.id AND o.active=TRUE
            WHERE p.account_key=? AND p.deleted=FALSE
            """,(r,n)->new Candidate(new Key(r.getString(1),r.getString(2)),normalize(r.getString(3)),(Long)r.getObject(4),(Long)r.getObject(5),r.getBoolean(6)),account);
        var settings=pricing.get(actor);
        DefaultCoupangPricing.validate(settings.shippingCost(),settings.commissionPercent(),settings.marginPercent());
        var codes=new HashSet<String>();for(var c:candidates)if(c.onSale()&&!c.code().isEmpty()&&c.code().length()<=100)codes.add(c.code());
        var evidence=sources.byCodes(actor,Set.copyOf(codes));
        var result=new HashMap<Key,Recommendation>();
        var optionCounts=new HashMap<String,Integer>();
        for(var c:candidates)optionCounts.merge(c.code(),1,Integer::sum);
        for(var c:candidates){if(!c.onSale())continue;var recommendation=evaluate(pricing,settings,c.price(),c.stock(),evidence.get(c.code()),optionCounts.getOrDefault(c.code(),0));if(recommendation!=null)result.put(c.key(),recommendation);}
        return result;
    }
    static Recommendation evaluate(CoupangPricing pricing,CoupangPricing.Settings settings,Long price,Long stock,CoupangRecommendationSources.Source source) {
        return evaluate(pricing,settings,price,stock,source,1);
    }
    static Recommendation evaluate(CoupangPricing pricing,CoupangPricing.Settings settings,Long price,Long stock,CoupangRecommendationSources.Source source,int couponOptions) {
        if(source==null)return null;
        var reasons=new ArrayList<String>();CoupangPricing.Calculation current=null,suggested=null;
        if("AVAILABLE".equals(source.availability())&&source.purchaseCost()!=null) {
            try {
                suggested=pricing.calculate(settings,source.purchaseCost(),null);
                if(price!=null&&price>0)current=pricing.calculate(settings,source.purchaseCost(),price);
                if(current!=null&&price>suggested.suggestedPrice())reasons.add("PRICE_HIGH");
                if(current!=null&&current.profit().signum()<0)reasons.add("NEGATIVE_MARGIN");
            }catch(InputValidationFailure invalid){/* Unusable price evidence must not hide independent stock evidence. */}
        }
        if("AVAILABLE".equals(source.availability())&&stock!=null&&stock==0&&!source.stocks().isEmpty())reasons.add("RESTOCK");
        var proposal=CoupangRecommendationSources.stockProposal(source.availability(),source.ownedQuantity(),source.ownedOptionCount(),couponOptions,source.stocks(),source.stockOptionCount());
        if(Set.of("UNAVAILABLE","SELECTED_MISSING").contains(Objects.toString(source.availability(),""))&&stock!=null&&source.ownedQuantity()!=null){
            if(source.ownedQuantity()==0&&stock>0)reasons.add("MARK_SOLD_OUT");
            else if(source.ownedQuantity()>0&&(proposal.selectionRequired()||!source.ownedQuantity().equals(stock)))reasons.add("OWNED_STOCK_MISMATCH");
        }
        return reasons.isEmpty()?null:new Recommendation(List.copyOf(reasons),source.productId(),suggested==null?null:suggested.suggestedPrice(),current==null?null:current.profit(),current==null?null:current.marginPercent(),source.stocks(),source.stockOptionCount(),source.previousPrice(),source.priceCheckedAt(),suggested!=null&&suggested.profit().signum()<0,proposal,source.previousAvailability(),source.availabilityCheckedAt(),source.availabilityReason());
    }
}
