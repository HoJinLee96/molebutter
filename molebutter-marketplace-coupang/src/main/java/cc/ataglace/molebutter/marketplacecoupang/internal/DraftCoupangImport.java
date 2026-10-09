package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDocuments;

import java.util.*;
import cc.ataglace.molebutter.marketplace.api.CoupangEditor;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

final class DraftCoupangImport {
    private DraftCoupangImport() {}
    static Document convert(CoupangEditor.EditorDocument source){
        if(source==null||source.basic()==null||source.options()==null||source.options().isEmpty())throw new cc.ataglace.molebutter.marketplace.api.MarketplaceFailure(cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.RESPONSE);
        var b=source.basic();var options=new ArrayList<Option>();var images=new ArrayList<Image>();var contents=new ArrayList<Content>();var mappings=new ArrayList<CoupangOption>();
        var first=source.options().getFirst();var registration=DraftCoupangProjection.fields(first.registration());
        String sku=registration.getOrDefault("externalVendorSku","");
        var common=new Common(sku,safe(b.sellerProductName()),safe(b.generalProductName()),safe(b.brand()),DraftCoupangProjection.fields(source.settings()).getOrDefault("manufacture",""),"","",registration.getOrDefault("modelNo",""),"",registration.getOrDefault("taxType",""),registration.getOrDefault("adultOnly",""));
        for(var option:source.options()){
            var id=UUID.randomUUID().toString();var fields=DraftCoupangProjection.fields(option.registration());
            var attrs=option.attributes().stream().filter(a->"EXPOSED".equals(a.exposed())).map(a->new Attribute(safe(a.name()),safe(a.value()))).toList();
            options.add(new Option(id,safe(option.itemName()),fields.getOrDefault("externalVendorSku",""),option.current()==null||option.current().salePrice()==null?"":Long.toString(option.current().salePrice()),option.current()==null||option.current().amountInStock()==null?"":Long.toString(option.current().amountInStock()),attrs));
            for(var i:option.images())if(i.url()!=null&&MarketplaceDocuments.https(i.url()))images.add(new Image(UUID.randomUUID().toString(),null,i.url(),"REPRESENTATION".equals(i.type()),i.order()==null?0:i.order(),id,i.type()));
            for(var c:option.contents()){
                String value=safe(c.content());if("IMAGE".equals(c.detailType())){if(!MarketplaceDocuments.https(value))value=cdn(value);if(value==null)continue;contents.add(new Content(UUID.randomUUID().toString(),"IMAGE",value,id));}
                else contents.add(new Content(UUID.randomUUID().toString(),"HTML","HTML".equals(c.type())?value:"<pre>"+escape(value)+"</pre>",id));
            }
            mappings.add(new CoupangOption(id,option.sellerProductItemId(),option.vendorItemId(),option.registration(),option.attributes(),option.notices(),option.certifications()));
        }
        var d=DraftCoupangProjection.fields(source.delivery());var delivery=new Delivery(d.get("deliveryMethod"),d.get("deliveryCompanyCode"),d.get("deliveryChargeType"),d.get("deliveryCharge"),d.get("freeShipOverAmount"),d.get("returnCharge"),d.get("deliveryChargeOnReturn"),d.get("remoteAreaDeliverable"),d.get("unionDeliveryType"),d.get("outboundShippingPlaceCode"),d.get("returnCenterCode"),d.get("returnChargeName"),d.get("companyContactNumber"),d.get("returnZipCode"),d.get("returnAddress"),d.get("returnAddressDetail"));
        var over=new Overrides(b.sellerProductName(),b.displayProductName(),null,List.of(),null,null);
        var config=new MarketConfig(b.displayCategoryCode(),over,new Coupang(b.sellerProductId(),source.delivery(),source.settings(),List.copyOf(mappings),source.documents(),source),null,null);
        return MarketplaceDocuments.normalize(new Document(null,null,common,List.copyOf(options),StockMode.OPTION,"",List.of(),new Media(List.copyOf(images),List.copyOf(contents)),delivery,List.of(Market.COUPANG),Map.of(Market.COUPANG,config)));
    }
    private static String safe(String s){return s==null?"":s;}
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String cdn(String s){if(s.matches("(?:vendor_inventory|image)/[a-zA-Z0-9_./%-]+"))return "https://img1a.coupangcdn.com/image/"+s.replaceFirst("^image/","");return null;}
}
