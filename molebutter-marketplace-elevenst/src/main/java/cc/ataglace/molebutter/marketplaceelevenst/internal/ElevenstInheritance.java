package cc.ataglace.molebutter.marketplaceelevenst.internal;

import java.util.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;

/** Durable per-field inheritance for CREATE inputs; live UPDATE observations stay independent. */
final class ElevenstInheritance {
    static final EditorState EMPTY = new EditorState(1,List.of(),"COMMON","COMMON",null,null,null,null,List.of());
    private ElevenstInheritance() {}
    static Document state(Document d,EditorState state){
        var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());var m=markets.get(Market.ELEVENST);
        markets.put(Market.ELEVENST,new MarketConfig(m.categoryCode(),m.overrides(),m.coupang(),m.naver(),m.esm(),m.lotteon(),m.addressBook(),new Elevenst(m.elevenst().editorInput(),state)));
        return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.copyOf(markets));
    }
    static Document merge(Document fresh,PartnerEditor.Input previous,EditorState s){
        if(previous==null)return state(fresh,EMPTY);
        if(s==null)return withInput(fresh,"ELEVENST",previous); // Older native inputs were entirely individual values.
        if(!Objects.equals(s.version(),1))throw invalid("11번가 상속 설정 버전을 확인해 주세요.");
        var overrides=new HashSet<>(MarketplaceDocuments.list(s.overrides(),2000));var base=input(fresh,"ELEVENST");var fields=new LinkedHashMap<>(base.fields());
        previous.fields().forEach((k,v)->{if(!fields.containsKey(k)||overrides.contains("fields."+k))fields.put(k,v);});
        var rows=base.options().stream().map(o->{var old=previous.options().stream().filter(v->v.id().equals(o.id())).findFirst().orElse(null);if(old==null)return o;
            String p="options."+o.id()+".";
            return new PartnerEditor.Option(o.id(),overrides.contains(p+"name")?old.name():o.name(),overrides.contains(p+"sku")?old.sku():o.sku(),overrides.contains(p+"price")?old.price():o.price(),overrides.contains(p+"quantity")?old.quantity():o.quantity(),overrides.contains(p+"attributes")?old.attributes():o.attributes(),overrides.contains(p+"usable")?old.usable():o.usable());}).toList();
        return state(withInput(fresh,"ELEVENST",new PartnerEditor.Input(fields,rows,overrides.contains("images")?previous.images():base.images(),overrides.contains("description")?previous.description():base.description(),previous.noticeType(),previous.notices(),previous.certifications())),s);
    }
    static Document mark(Document before,Document after,ObjectMapper json){
        var m=before.markets().get(Market.ELEVENST);var s=m.elevenst().editorState();if(s==null)return after;
        var keys=new LinkedHashSet<>(s.overrides());var a=input(before,"ELEVENST");var b=input(after,"ELEVENST");
        for(var c:diff(a,b,json)){
            String key=c.path().substring(8);
            if(!key.equals("options")){keys.add(key);continue;}
            for(var row:b.options()){var old=a.options().stream().filter(v->v.id().equals(row.id())).findFirst().orElse(null);if(old==null)continue;
                var x=json.valueToTree(old);var y=json.valueToTree(row);for(String field:List.of("name","sku","price","quantity","attributes","usable"))if(!Objects.equals(x.path(field),y.path(field)))keys.add("options."+row.id()+"."+field);
            }
        }
        return state(after,new EditorState(1,List.copyOf(keys),s.pricing(),s.optionStructure(),s.imageSource(),s.imageOptionId(),s.descriptionSource(),s.descriptionOptionId(),s.unmapped()));
    }
}
