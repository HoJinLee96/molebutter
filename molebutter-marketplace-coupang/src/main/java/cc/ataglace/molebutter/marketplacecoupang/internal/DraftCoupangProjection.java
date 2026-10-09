package cc.ataglace.molebutter.marketplacecoupang.internal;


import java.util.*;
import cc.ataglace.molebutter.marketplace.api.CoupangCatalog;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

/** Read-only effective inputs used for validation, never sent to Coupang. */
final class DraftCoupangProjection {
    private DraftCoupangProjection() {}
    static String inherited(String override,String common){return override==null?common:override;}
    static OptionOverride override(MarketConfig market,Option option){return market.overrides()==null?null:market.overrides().options().stream().filter(o->o.optionId().equals(option.id())).findFirst().orElse(null);}
    static String name(Document d,MarketConfig m){return m.overrides()==null?d.common().name():inherited(m.overrides().name(),d.common().name());}
    static Map<String,String> fields(List<CoupangCatalog.Field> fields){var result=new LinkedHashMap<String,String>();if(fields!=null)for(var field:fields)if(field!=null)result.put(field.name(),field.value());return result;}
    static Map<String,String> delivery(Document d,MarketConfig m){
        var p=d.delivery();var values=new LinkedHashMap<String,String>();
        values.put("deliveryMethod",p.method());values.put("deliveryCompanyCode",p.carrier());values.put("deliveryChargeType",p.chargeType());values.put("deliveryCharge",p.charge());values.put("freeShipOverAmount",p.freeOver());values.put("deliveryChargeOnReturn",p.initialReturnCharge());values.put("remoteAreaDeliverable",p.remoteArea());values.put("unionDeliveryType",p.bundle());values.put("outboundShippingPlaceCode",p.outboundCode());values.put("returnCenterCode",p.returnCode());values.put("returnChargeName",p.returnName());values.put("companyContactNumber",p.returnContact());values.put("returnZipCode",p.returnZip());values.put("returnAddress",p.returnAddress());values.put("returnAddressDetail",p.returnAddressDetail());values.put("returnCharge",p.returnCharge());
        if(m.coupang()!=null)values.putAll(fields(m.coupang().delivery()));return values;
    }
    static Map<String,String> settings(Document d,MarketConfig m){var values=new LinkedHashMap<String,String>();values.put("manufacture",d.common().manufacturer());if(m.coupang()!=null)values.putAll(fields(m.coupang().settings()));return values;}
    static CoupangOption optionConfig(MarketConfig m,Option o){return m.coupang()==null?null:nullableList(m.coupang().options()).stream().filter(c->Objects.equals(c.optionId(),o.id())).findFirst().orElse(null);}
    static Map<String,String> registration(Document d,MarketConfig m,Option o){
        var over=override(m,o);var values=new LinkedHashMap<String,String>();
        values.put("externalVendorSku",over==null?o.sku():inherited(over.sku(),o.sku()));values.put("salePrice",over==null?o.price():inherited(over.price(),o.price()));values.put("maximumBuyCount",d.stockMode()==StockMode.PRODUCT?d.productQuantity():over==null?o.quantity():inherited(over.quantity(),o.quantity()));values.put("taxType",d.common().taxType());values.put("adultOnly",d.common().adultOnly());values.put("modelNo",d.common().model());
        var config=optionConfig(m,o);if(config!=null)values.putAll(fields(config.registration()));return values;
    }
    static Map<String,String> attributes(MarketConfig m,Option o){var values=new LinkedHashMap<String,String>();for(var a:o.attributes())values.put(a.name(),a.value());var config=optionConfig(m,o);if(config!=null)for(var a:nullableList(config.attributes()))values.put(a.name(),a.value());return values;}
    static Map<String,String> notices(Document d,MarketConfig m,Option o){
        var values=new LinkedHashMap<String,String>();values.put("소재",d.common().material());values.put("제조국",d.common().origin());values.put("A/S 책임자와 전화번호",d.common().afterService());
        var config=optionConfig(m,o);if(config!=null)for(var n:nullableList(config.notices()))values.put(n.category()+"\u0001"+n.name(),n.content());return values;
    }
    static <T> List<T> nullableList(List<T> values){return values==null?List.of():values;}
    static List<Image> images(Document d,MarketConfig m,Option o){return d.media().images().stream().filter(i->i.optionId()==null||i.optionId().equals(o.id())).filter(i->m.overrides()==null||m.overrides().imageIds()==null||m.overrides().imageIds().contains(i.id())).toList();}
}
