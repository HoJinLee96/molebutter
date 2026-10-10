package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;

import java.nio.charset.StandardCharsets;
import java.util.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.CoupangEditor;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.*;

/** Fresh read projection, distinct from the durable common reference and old import snapshot. */
final class CoupangEditingProjection {
    private CoupangEditingProjection() {}
    static Document latest(Document reference,Mapping mapping,CoupangEditor.EditorDocument source){
        if(mapping==null||source==null||source.basic()==null||!Objects.equals(mapping.sellerProductId(),source.basic().sellerProductId()))throw invalid();
        var projected=DraftCoupangImport.convert(source);var config=projected.markets().get(Market.COUPANG);
        if(mapping.options().size()!=source.options().size())throw invalid();
        var remap=new HashMap<String,String>();var options=new ArrayList<Option>();var linked=new ArrayList<CoupangOption>();var used=new HashSet<String>();
        for(var generated:config.coupang().options()){
            var old=mapping.options().stream().filter(o->Objects.equals(o.sellerProductItemId(),generated.sellerProductItemId())).findFirst().orElseThrow(CoupangEditingProjection::invalid);
            if(!used.add(old.optionId())||old.vendorItemId()!=null&&!Objects.equals(old.vendorItemId(),generated.vendorItemId()))throw invalid();
            remap.put(generated.optionId(),old.optionId());
            var value=projected.options().stream().filter(o->o.id().equals(generated.optionId())).findFirst().orElseThrow();
            options.add(new Option(old.optionId(),value.name(),value.sku(),value.price(),value.quantity(),value.attributes()));
            linked.add(new CoupangOption(old.optionId(),generated.sellerProductItemId(),generated.vendorItemId(),generated.registration(),generated.attributes(),generated.notices(),generated.certifications()));
        }
        // Remote array order can change; internal option identities and common presentation order stay stable.
        options.sort(Comparator.comparingInt(o->index(mapping,o.id())));linked.sort(Comparator.comparingInt(o->index(mapping,o.optionId())));
        var images=new ArrayList<Image>();for(var i:projected.media().images()){
            String option=remap.get(i.optionId());String key=option+"|image|"+i.order()+"|"+i.imageType()+"|"+i.url();
            images.add(new Image(stable(key),i.assetId(),i.url(),i.representative(),i.order(),option,i.type()));
        }
        var contents=new ArrayList<Content>();var positions=new HashMap<String,Integer>();for(var c:projected.media().contents()){
            String option=remap.get(c.optionId());int n=positions.merge(option,1,Integer::sum);
            contents.add(new Content(stable(option+"|content|"+n),c.type(),c.value(),option));
        }
        var cp=config.coupang();var mapped=new Coupang(cp.sellerProductId(),cp.delivery(),cp.settings(),List.copyOf(linked),cp.documents(),source);
        var market=new MarketConfig(config.categoryCode(),config.overrides(),mapped,null,null);
        return new Document(reference.id(),reference.revision(),projected.common(),List.copyOf(options),StockMode.OPTION,"",projected.services(),new Media(List.copyOf(images),List.copyOf(contents)),projected.delivery(),List.of(Market.COUPANG),Map.of(Market.COUPANG,market));
    }
    private static int index(Mapping mapping,String id){for(int n=0;n<mapping.options().size();n++)if(mapping.options().get(n).optionId().equals(id))return n;throw invalid();}
    private static String stable(String key){return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();}
    private static InputValidationFailure invalid(){return new InputValidationFailure("쿠팡 상품·옵션 연결이 변경되었습니다. 연결을 확인해 주세요.");}
}
