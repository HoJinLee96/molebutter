package cc.ataglace.molebutter.marketplace.api;

import java.util.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;

final class PartnerInputChanges {
    private PartnerInputChanges() {}
    public static List<MarketplaceEditing.Change> diff(PartnerEditor.Input before,PartnerEditor.Input after,ObjectMapper json){
        var a=parts(before);var b=parts(after);var result=new ArrayList<MarketplaceEditing.Change>();var keys=new LinkedHashSet<>(a.keySet());keys.addAll(b.keySet());
        for(String key:keys)if(!Objects.equals(json.valueToTree(a.get(key)),json.valueToTree(b.get(key))))result.add(new MarketplaceEditing.Change("partner."+key,null,b.get(key)));return List.copyOf(result);
    }
    public static Map<String,Object> parts(PartnerEditor.Input input){var out=new LinkedHashMap<String,Object>();input.fields().forEach((k,v)->out.put("fields."+k,v));out.put("options",input.options());out.put("images",input.images());out.put("description",input.description());out.put("noticeType",input.noticeType());out.put("notices",input.notices());out.put("certifications",input.certifications());return out;}
    public static PartnerEditor.Input apply(PartnerEditor.Input input,List<MarketplaceEditing.Change> changes,ObjectMapper json){
        var root=new LinkedHashMap<String,Object>();root.put("fields",new LinkedHashMap<>(input.fields()));root.put("options",input.options());root.put("images",input.images());root.put("description",input.description());root.put("noticeType",input.noticeType());root.put("notices",input.notices());root.put("certifications",input.certifications());
        @SuppressWarnings("unchecked") var fields=(Map<String,Object>)root.get("fields");var seen=new HashSet<String>();
        for(var c:MarketplaceDocuments.list(changes,500)){if(c==null||c.path()==null||!c.path().startsWith("partner.")||!seen.add(c.path()+"|"+c.optionId()))throw invalid("상품 변경 항목을 확인해 주세요.");String key=c.path().substring(8);if(c.optionId()!=null){if(!key.startsWith("options.")||!Set.of("name","sku","price","quantity","attributes","usable").contains(key.substring(8)))throw invalid("옵션 변경 항목을 확인해 주세요.");var rows=json.valueToTree(root.get("options"));boolean found=false;for(var row:rows)if(c.optionId().equals(row.path("id").asString())){row.asObject().set(key.substring(8),json.valueToTree(c.value()));found=true;break;}if(!found)throw invalid("옵션 연결을 확인해 주세요.");root.put("options",rows);continue;}if(key.startsWith("fields."))fields.put(key.substring(7),c.value());else if(root.containsKey(key))root.put(key,c.value());else throw invalid("지원하지 않는 상품 변경 항목입니다.");}
        return json.convertValue(root,PartnerEditor.Input.class);
    }
    public static MarketplaceEditing.PendingRebase rebase(PartnerEditor.Input before,PartnerEditor.Input current,List<MarketplaceEditing.Change> changes,ObjectMapper json){
        var desired=apply(before,changes,json);var old=parts(before);var now=parts(current);var wanted=parts(desired);
        var pending=new ArrayList<MarketplaceEditing.Change>();var conflicts=new ArrayList<MarketplaceEditing.Conflict>();
        for(var change:changes){String key=change.path().substring(8);Object ov=value(before,change,json),nv=value(current,change,json),wv=value(desired,change,json);var a=json.valueToTree(ov);var b=json.valueToTree(nv);var c=json.valueToTree(wv);
            if(Objects.equals(b,c))continue;
            pending.add(change);if(!Objects.equals(a,b))conflicts.add(new MarketplaceEditing.Conflict(change.path(),change.optionId(),ov,nv,wv));
        }
        return new MarketplaceEditing.PendingRebase(List.copyOf(pending),List.copyOf(conflicts));
    }

    static Object value(PartnerEditor.Input input,MarketplaceEditing.Change c,ObjectMapper json){if(c.optionId()==null)return parts(input).get(c.path().substring(8));var row=input.options().stream().filter(o->o.id().equals(c.optionId())).findFirst().orElseThrow(()->invalid("옵션 연결을 확인해 주세요."));return json.convertValue(json.valueToTree(row).path(c.path().substring("partner.options.".length())),Object.class);}
}
