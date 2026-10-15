package cc.ataglace.molebutter.marketplace.api;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

/** Protocol-independent validation and durable form projection shared by partner adapters. */
public final class PartnerFormSupport {
    private PartnerFormSupport() {}
    public static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
    public static String id(String seed){return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();}
    /** Shape validation at the draft boundary; provider adapters still enforce their field allowlist. */
    public static PartnerEditor.Input structure(PartnerEditor.Input input){
        if(input==null)return null;
        if(input.fields()!=null&&input.fields().size()>100)throw invalid("상품 입력 항목 수를 확인해 주세요.");
        var fields=new ArrayList<PartnerEditor.Field>();
        if(input.fields()!=null)for(String key:input.fields().keySet()){
            if(key==null||!key.matches("[A-Za-z][A-Za-z0-9_.]{0,199}"))throw invalid("상품 입력 항목 이름을 확인해 주세요.");
            fields.add(new PartnerEditor.Field(key,key,"","text",false,List.of()));
        }
        return normalize(input,new PartnerEditor.Schema(List.copyOf(fields),500,100,""));
    }
    public static PartnerEditor.Input normalize(PartnerEditor.Input input,PartnerEditor.Schema schema){
        if(input==null)input=new PartnerEditor.Input(Map.of(),List.of(),List.of(),"","",List.of(),List.of());
        var allowed=new HashMap<String,PartnerEditor.Field>();for(var f:schema.fields())allowed.put(f.key(),f);
        var fields=new LinkedHashMap<String,Object>();
        if(input.fields()!=null)for(var e:input.fields().entrySet()){
            var spec=allowed.get(e.getKey());if(spec==null)throw invalid("지원하지 않는 상품 입력 항목입니다: "+e.getKey());
            if(e.getValue()!=null&&!(e.getValue() instanceof String)&&!(e.getValue() instanceof Number)&&!(e.getValue() instanceof Boolean))throw invalid("상품 입력 형식을 확인해 주세요.");
            String value=Objects.toString(e.getValue(),"");MarketplaceDocuments.text(value,10000);
            if(!value.isEmpty()&&!spec.choices().isEmpty()&&!spec.choices().contains(value))throw invalid(spec.label()+" 값을 확인해 주세요.");
            fields.put(e.getKey(),value);
        }
        var ids=new HashSet<String>();var options=new ArrayList<PartnerEditor.Option>();
        for(var o:MarketplaceDocuments.list(input.options(),schema.maxOptions())){
            String id=MarketplaceDocuments.uuid(o.id());if(!ids.add(id))throw invalid("옵션 식별자가 중복되었습니다.");
            var attrs=new ArrayList<Attribute>();for(var a:MarketplaceDocuments.list(o.attributes(),5))attrs.add(new Attribute(MarketplaceDocuments.text(a.name(),100),MarketplaceDocuments.text(a.value(),200)));
            options.add(new PartnerEditor.Option(id,MarketplaceDocuments.text(o.name(),200),MarketplaceDocuments.text(o.sku(),100),MarketplaceDocuments.text(o.price(),100),MarketplaceDocuments.text(o.quantity(),100),List.copyOf(attrs),!Boolean.FALSE.equals(o.usable())));
        }
        var images=new ArrayList<PartnerEditor.Image>();var imageIds=new HashSet<String>();
        for(var i:MarketplaceDocuments.list(input.images(),schema.maxImages())){
            var id=MarketplaceDocuments.uuid(i.id());if(!imageIds.add(id))throw invalid("이미지 식별자가 중복되었습니다.");
            String asset=i.assetId()==null?null:MarketplaceDocuments.uuid(i.assetId());String url=MarketplaceDocuments.text(i.url(),4000);
            if(asset==null&&!url.isBlank()&&!MarketplaceDocuments.https(url))throw invalid("이미지는 HTTPS 주소를 사용해 주세요.");
            if(i.order()<0||i.order()>1000)throw invalid("이미지 순서를 확인해 주세요.");
            images.add(new PartnerEditor.Image(id,asset,asset==null?url:"/api/marketplaces/assets/"+asset,i.representative(),i.order()));
        }
        if(images.stream().filter(PartnerEditor.Image::representative).count()>1)throw invalid("대표 이미지는 한 개만 선택해 주세요.");
        var notices=new ArrayList<Attribute>();for(var a:MarketplaceDocuments.list(input.notices(),100))notices.add(new Attribute(MarketplaceDocuments.text(a.name(),100),MarketplaceDocuments.text(a.value(),10000)));
        var certs=new ArrayList<PartnerEditor.Certification>();for(var c:MarketplaceDocuments.list(input.certifications(),100))certs.add(new PartnerEditor.Certification(MarketplaceDocuments.text(c.type(),100),MarketplaceDocuments.text(c.number(),200),MarketplaceDocuments.text(c.agency(),200)));
        return new PartnerEditor.Input(Map.copyOf(fields),List.copyOf(options),List.copyOf(images),MarketplaceDocuments.text(input.description(),2*1024*1024),MarketplaceDocuments.text(input.noticeType(),100),List.copyOf(notices),List.copyOf(certs));
    }
    public static void validate(PartnerEditor.Input i,PartnerEditor.Schema schema){
        for(var f:schema.fields())if(f.required()&&Objects.toString(i.fields().get(f.key()),"").isBlank())throw invalid(f.label()+"을 입력해 주세요.");
        if(i.options().isEmpty())throw invalid("옵션을 한 개 이상 입력해 주세요.");
        var names=new HashSet<String>();for(var o:i.options()){
            if(o.name().isBlank()||!names.add(o.name().strip()))throw invalid("옵션명은 비어 있거나 중복될 수 없습니다.");
            number(o.price(),0,999999999,"판매가");number(o.quantity(),0,999999999,"재고");
        }
        if(i.images().isEmpty()||i.images().stream().filter(PartnerEditor.Image::representative).count()!=1)throw invalid("대표 이미지를 선택해 주세요.");
        if(i.description().isBlank())throw invalid("상세설명을 입력해 주세요.");
        if(i.description().contains("blob:"))throw invalid("설명 이미지 업로드를 완료해 주세요.");
        if(i.noticeType().isBlank()||i.notices().isEmpty())throw invalid("상품고시 유형과 항목을 입력해 주세요.");
    }
    public static long number(String text,long min,long max,String label){try{if(text==null||!text.matches("[0-9]+"))throw new NumberFormatException();long n=Long.parseLong(text);if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw invalid(label+" 범위를 확인해 주세요.");}}
    public static PartnerEditor.Input fromCommon(Document d,Map<String,String> keys,Map<String,Object> defaults){return PartnerInputProjection.fromCommon(d,keys,defaults);}
    public static Document withInput(Document d,String market,PartnerEditor.Input input){return PartnerInputProjection.withInput(d,market,input);}
    public static PartnerEditor.Input input(Document d,String market){return PartnerInputProjection.input(d,market);}
    public static List<MarketplaceEditing.Change> diff(PartnerEditor.Input before,PartnerEditor.Input after,ObjectMapper json){return PartnerInputChanges.diff(before,after,json);}
    public static Map<String,Object> parts(PartnerEditor.Input input){return PartnerInputChanges.parts(input);}
    public static List<MarketplaceEditing.Change> comparisonFields(PartnerEditor.Input input,ObjectMapper json){
        var out=new ArrayList<MarketplaceEditing.Change>();parts(input).forEach((k,v)->{if(!k.equals("options"))out.add(new MarketplaceEditing.Change("partner."+k,null,v));});
        for(var o:input.options())for(String field:List.of("name","sku","price","quantity","attributes","usable"))out.add(new MarketplaceEditing.Change("partner.options."+field,o.id(),json.convertValue(json.valueToTree(o).path(field),Object.class)));
        return List.copyOf(out);
    }
    public static List<MarketplaceEditing.Change> granular(PartnerEditor.Input before,List<MarketplaceEditing.Change> changes,ObjectMapper json){
        var after=apply(before,changes,json);var a=comparisonFields(before,json);var b=comparisonFields(after,json);var out=new ArrayList<MarketplaceEditing.Change>();
        boolean same=before.options().stream().map(PartnerEditor.Option::id).toList().equals(after.options().stream().map(PartnerEditor.Option::id).toList());
        for(var c:changes)if(!c.path().equals("partner.options"))out.add(c);
        if(changes.stream().anyMatch(c->c.path().equals("partner.options"))){if(!same)throw invalid("기존 옵션의 연결 변경은 지원하지 않습니다.");for(var v:b)if(v.optionId()!=null&&a.stream().noneMatch(o->o.path().equals(v.path())&&Objects.equals(o.optionId(),v.optionId())&&Objects.equals(json.valueToTree(o.value()),json.valueToTree(v.value()))))out.add(v);}
        return List.copyOf(out);
    }
    public static PartnerEditor.Input apply(PartnerEditor.Input input,List<MarketplaceEditing.Change> changes,ObjectMapper json){return PartnerInputChanges.apply(input,changes,json);}
    public static MarketplaceEditing.PendingRebase rebase(PartnerEditor.Input before,PartnerEditor.Input current,List<MarketplaceEditing.Change> changes,ObjectMapper json){return PartnerInputChanges.rebase(before,current,changes,json);}
    public static Document document(String market,PartnerEditor.Input input,List<Market> selected,Map<String,String> keys){return PartnerInputProjection.document(market,input,selected,keys);}
}
