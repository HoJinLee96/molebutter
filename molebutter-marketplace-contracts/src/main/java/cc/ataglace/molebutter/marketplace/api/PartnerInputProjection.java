package cc.ataglace.molebutter.marketplace.api;

import java.util.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;

final class PartnerInputProjection {
    private PartnerInputProjection() {}
    public static PartnerEditor.Input fromCommon(Document d,Map<String,String> keys,Map<String,Object> defaults){
        var c=d.common();var values=Map.of("name",c.name(),"code",c.productCode(),"brand",c.brand(),"manufacturer",c.manufacturer(),"model",c.model(),"afterService",c.afterService());
        var fields=new LinkedHashMap<String,Object>(defaults);keys.forEach((common,remote)->fields.put(remote,values.getOrDefault(common,"")));
        var options=d.options().stream().map(o->new PartnerEditor.Option(o.id(),o.name(),o.sku(),o.price(),d.stockMode()==StockMode.PRODUCT?(d.options().size()==1?d.productQuantity():""):o.quantity(),o.attributes(),true)).toList();
        var images=d.media()==null?List.<PartnerEditor.Image>of():d.media().images().stream().filter(i->i.optionId()==null).map(i->new PartnerEditor.Image(i.id(),i.assetId(),i.url(),i.representative(),i.order())).toList();
        String description=d.media()==null?"":d.media().contents().stream().filter(i->i.optionId()==null).map(i->"IMAGE".equals(i.type())?"<img src=\""+i.value()+"\" />":i.value()).reduce((a,b)->a+"\n"+b).orElse("");
        return new PartnerEditor.Input(Map.copyOf(fields),options,images,description,"",List.of(),List.of());
    }
    public static Document withInput(Document d,String market,PartnerEditor.Input input){
        var key=Market.valueOf(market);var markets=new EnumMap<Market,MarketConfig>(Market.class);if(d.markets()!=null)markets.putAll(d.markets());var m=markets.get(key);if(m==null)m=new MarketConfig("",null,null,null,null,null);
        Esm esm=m.esm();Lotteon lot=m.lotteon();Elevenst eleven=m.elevenst();
        if(key==Market.ELEVENST)eleven=new Elevenst(input,eleven==null?null:eleven.editorState());
        else if(key==Market.LOTTEON)lot=new Lotteon(lot==null?null:lot.brandId(),input,lot==null?null:lot.editorState(),lot==null?null:lot.nativeInput());
        else esm=new Esm(esm==null?(key==Market.GMARKET?"2":"1"):esm.siteId(),esm==null?null:esm.goodsStatus(),esm==null?null:esm.shippingPolicyId(),esm==null?null:esm.returnPolicyId(),esm==null?null:esm.itemCode(),esm==null?List.of():esm.attributes(),esm==null?List.of():esm.notices(),input,esm==null?null:esm.editorState());
        markets.put(key,new MarketConfig(m.categoryCode(),m.overrides(),m.coupang(),m.naver(),esm,lot,m.addressBook(),eleven));
        return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.copyOf(markets));
    }
    public static PartnerEditor.Input input(Document d,String market){var m=d.markets().get(Market.valueOf(market));return m==null?null:"ELEVENST".equals(market)?m.elevenst()==null?null:m.elevenst().editorInput():"LOTTEON".equals(market)?m.lotteon()==null?null:m.lotteon().editorInput():m.esm()==null?null:m.esm().editorInput();}
    public static Document document(String market,PartnerEditor.Input input,List<Market> selected,Map<String,String> keys){
        var f=input.fields();var reverse=new HashMap<String,String>();keys.forEach((common,remote)->reverse.put(common,Objects.toString(f.get(remote),"")));
        var common=new Common(reverse.getOrDefault("code",""),reverse.getOrDefault("name",""),"",reverse.getOrDefault("brand",""),reverse.getOrDefault("manufacturer",""),"","",reverse.getOrDefault("model",""),reverse.getOrDefault("afterService",""),"","");
        var options=input.options().stream().map(o->new Option(o.id(),o.name(),o.sku(),o.price(),o.quantity(),o.attributes())).toList();
        var images=input.images().stream().map(i->new Image(i.id(),i.assetId(),i.url(),i.representative(),i.order(),null)).toList();
        var contents=input.description().isEmpty()?List.<Content>of():List.of(new Content(id(market+":description"),"HTML",input.description(),null));
        var d=new Document(null,null,common,options,StockMode.OPTION,"",List.of(),new Media(images,contents),null,selected,Map.of());
        return withInput(d,market,input);
    }
}
