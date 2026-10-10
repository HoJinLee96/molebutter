package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDocuments;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditConflict;

import java.util.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Only app-owned editable fields are accepted; no external IDs, source objects or arbitrary JSON paths. */
final class CoupangEditPatch {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> COMMON=Set.of("productCode","name","productName","brand","manufacturer","origin","material","model","afterService","taxType","adultOnly");
    private static final Set<String> OPTION=Set.of("name","sku","price","quantity");
    private static final Set<String> DELIVERY=Set.of("method","carrier","chargeType","charge","freeOver","returnCharge","initialReturnCharge","remoteArea","bundle","outboundCode","returnCode","returnName","returnContact","returnZip","returnAddress","returnAddressDetail");
    private static final Set<String> OVERRIDE=Set.of("name","productName","brand","description","imageIds");
    private static final Set<String> OPTION_LIST=Set.of("registration","attributes","notices","certifications");
    private static final List<String> FIELDS=List.of("markets.COUPANG.coupang.settings","markets.COUPANG.coupang.delivery","markets.COUPANG.coupang.options.registration");
    private CoupangEditPatch() {}
    static Document apply(Document document,List<MarketplaceEditing.Change> changes){return apply(document,changes,JSON);}
    /** Collection editors submit every row; retain fresh rows that the user did not change. */
    static List<MarketplaceEditing.Change> rebaseCollections(Document observed,Document latest,List<MarketplaceEditing.Change> changes,ObjectMapper json){
        var result=new ArrayList<MarketplaceEditing.Change>();
        for(var c:changes){
            if(!rowCollection(c)){result.add(c);continue;}
            JsonNode wanted=json.valueToTree(c.value());validateValue(c,wanted,json);
            var before=rows(value(observed,c,json),c);var now=rows(value(latest,c,json),c);var next=rows(wanted,c);
            if(c.path().endsWith(".notices")){
                var categories=new HashSet<String>();for(var row:next.values())categories.add(requiredKey(row,"category"));
                for(var entry:now.entrySet())if(categories.size()==1&&!before.containsKey(entry.getKey())&&!categories.contains(requiredKey(entry.getValue(),"category")))throw new MarketplaceEditConflict(c.path(),value(observed,c,json),value(latest,c,json),wanted);
            }
            var selected=new LinkedHashSet<String>();selected.addAll(before.keySet());selected.addAll(next.keySet());
            var merged=new LinkedHashMap<>(now);
            for(var key:selected){var old=before.get(key);var desired=next.get(key);if(Objects.equals(old,desired))continue;
                var fresh=now.get(key);if(!Objects.equals(old,fresh)&&!Objects.equals(fresh,desired))throw new MarketplaceEditConflict(c.path(),old,fresh,desired);
                if(desired==null)merged.remove(key);else merged.put(key,desired);
            }
            var array=json.createArrayNode();merged.values().forEach(array::add);result.add(new MarketplaceEditing.Change(c.path(),c.optionId(),array));
        }
        return List.copyOf(result);
    }
    static boolean rowCollection(MarketplaceEditing.Change c){return c.path().endsWith(".attributes")||c.path().endsWith(".notices")||c.path().endsWith(".certifications")||c.path().endsWith(".documents");}
    private static LinkedHashMap<String,JsonNode> rows(JsonNode values,MarketplaceEditing.Change c){
        var result=new LinkedHashMap<String,JsonNode>();for(var row:values){String key;
            if(c.path().endsWith(".notices"))key=requiredKey(row,"category")+"\u0000"+requiredKey(row,"name");
            else key=requiredKey(row,c.path().endsWith(".attributes")?"name":c.path().endsWith(".certifications")?"type":"templateName");
            if(result.putIfAbsent(key,row)!=null)throw invalid("같은 수정 항목을 중복 입력할 수 없습니다.");
        }return result;
    }
    private static String requiredKey(JsonNode row,String field){String key=text(row.path(field));if(key==null||key.isBlank())throw invalid("수정 목록의 항목명을 확인해 주세요.");return key;}
    /** A form sends a field collection; only entries actually changed from its observation become intent. */
    static List<MarketplaceEditing.Change> expand(Document observed,List<MarketplaceEditing.Change> changes,ObjectMapper json){
        var result=new ArrayList<MarketplaceEditing.Change>();for(var c:changes){
            // Legacy override inputs name the same remote scalar as the common edit fields.
            // Compare the actual observed value and reject two intents for one physical field.
            c=canonical(c);
            String shared=Map.of("common.model","modelNo","common.taxType","taxType","common.adultOnly","adultOnly").get(c.path());
            if(shared!=null){
                var incoming=json.valueToTree(c.value());validateValue(c,incoming,json);var market=observed.markets().get(Market.COUPANG);if(market==null||market.coupang()==null)throw invalid("쿠팡 옵션 연결을 확인해 주세요.");
                for(var row:market.coupang().options()){String path="markets.COUPANG.coupang.options.registration."+shared;var selected=new MarketplaceEditing.Change(path,row.optionId(),c.value());if(!value(observed,selected,json).equals(incoming))result.add(selected);}
                continue;
            }
            if(!FIELDS.contains(c.path())){result.add(c);continue;}
            var old=value(observed,c,json);var incoming=json.valueToTree(c.value());validateValue(c,incoming,json);var next=new LinkedHashMap<String,String>();
            for(var row:incoming){String key=text(row.path("name")),v=text(row.path("value"));if(key==null||v==null||next.putIfAbsent(key,v)!=null)throw invalid("설정 항목과 값을 확인해 주세요.");}
            var before=new HashMap<String,String>();for(var row:old)before.put(text(row.path("name")),text(row.path("value")));
            for(var key:before.keySet())if(!next.containsKey(key))throw invalid("기존 설정 항목 삭제는 지원하지 않습니다.");
            for(var e:next.entrySet())if(!Objects.equals(before.get(e.getKey()),e.getValue()))result.add(new MarketplaceEditing.Change(c.path()+"."+e.getKey(),c.optionId(),e.getValue()));
        }var normalized=result.stream().map(CoupangEditPatch::canonical).toList();check(normalized);return normalized;
    }
    private static MarketplaceEditing.Change canonical(MarketplaceEditing.Change c){
        String path=Map.of("markets.COUPANG.overrides.name","common.name","markets.COUPANG.overrides.brand","common.brand","markets.COUPANG.coupang.settings.manufacture","common.manufacturer","markets.COUPANG.coupang.options.registration.externalVendorSku","options.sku").get(c.path());
        String prefix="markets.COUPANG.coupang.delivery.";if(c.path().startsWith(prefix))path="delivery."+DefaultCoupangWriteGateway.deliveryInput(c.path().substring(prefix.length()));
        return path==null?c:new MarketplaceEditing.Change(path,c.optionId(),c.value());
    }
    static Document apply(Document document,List<MarketplaceEditing.Change> changes,ObjectMapper json){
        check(changes);ObjectNode root=(ObjectNode)json.valueToTree(document);
        for(var c:changes){var value=json.valueToTree(c.value());validateValue(c,value,json);assign(root,c,value,json);aliases(root,c,value,json);}
        try{return MarketplaceDocuments.normalize(json.treeToValue(root,Document.class));}catch(InputValidationFailure e){throw e;}catch(RuntimeException malformed){throw invalid("수정 입력의 형식을 확인해 주세요.");}
    }
    static JsonNode value(Document document,MarketplaceEditing.Change change,ObjectMapper json){
        var root=json.valueToTree(document);String path=change.path();
        String field=fieldPrefix(path);if(field!=null){var base=new MarketplaceEditing.Change(field,change.optionId(),List.of());for(var row:value(document,base,json))if(path.substring(field.length()+1).equals(text(row.path("name"))))return row.path("value");return json.stringNode("");}
        if(path.startsWith("options."))return option(root.path("options"),change.optionId(),"id").path(path.substring(8));
        if(path.startsWith("markets.COUPANG.coupang.options."))return option(root.path("markets").path("COUPANG").path("coupang").path("options"),change.optionId(),"optionId").path(path.substring("markets.COUPANG.coupang.options.".length()));
        if((path.equals("media.images")||path.equals("media.contents"))&&change.optionId()!=null){var selected=json.createArrayNode();for(var row:root.path("media").path(path.substring(6)))if(Objects.equals(change.optionId(),text(row.path("optionId"))))selected.add(row);return selected;}
        JsonNode at=root;for(var part:path.split("\\."))at=at.path(part);return at;
    }
    static boolean equivalent(JsonNode a,JsonNode b,MarketplaceEditing.Change c){
        if(c.path().equals("options.price")||c.path().equals("options.quantity"))return Objects.equals(number(a),number(b));
        if(c.path().startsWith("media."))return media(a).equals(media(b));
        if(c.path().endsWith(".registration.searchTags")&&a.isString()&&b.isString())return searchTags(a.asString()).equals(searchTags(b.asString()));
        return a.equals(b);
    }
    static void check(List<MarketplaceEditing.Change> changes){
        if(changes==null||changes.size()>200)throw invalid("변경 항목은 200개 이하로 선택해 주세요.");
        var seen=new HashSet<String>();for(var c:changes){
            if(c==null||c.path()==null||c.value()==null)throw invalid("변경할 항목과 값을 확인해 주세요.");String p=c.path();
            boolean option=p.startsWith("options.")||p.startsWith("markets.COUPANG.coupang.options.");
            boolean allowed=Set.of("stockMode","productQuantity").contains(p)||p.startsWith("common.")&&COMMON.contains(p.substring(7))||p.startsWith("options.")&&OPTION.contains(p.substring(8))||p.startsWith("delivery.")&&DELIVERY.contains(p.substring(9))||Set.of("media.images","media.contents").contains(p)||p.startsWith("markets.COUPANG.overrides.")&&OVERRIDE.contains(p.substring("markets.COUPANG.overrides.".length()))||Set.of("markets.COUPANG.coupang.settings","markets.COUPANG.coupang.delivery","markets.COUPANG.coupang.documents").contains(p)||p.startsWith("markets.COUPANG.coupang.options.")&&OPTION_LIST.contains(p.substring("markets.COUPANG.coupang.options.".length()));
            String prefix=fieldPrefix(p);if(prefix!=null)allowed=DefaultCoupangWriteGateway.editableField(prefix,p.substring(prefix.length()+1));
            if(!allowed||option&&c.optionId()==null||!option&&!p.startsWith("media.")&&c.optionId()!=null||c.optionId()!=null&&!c.optionId().matches("[a-fA-F0-9-]{36}"))throw invalid("수정할 수 없는 항목입니다: "+p);
            if(!seen.add(p+"|"+c.optionId()))throw invalid("같은 수정 항목을 중복 선택할 수 없습니다.");
        }
    }
    private static void validateValue(MarketplaceEditing.Change c,JsonNode value,ObjectMapper json){
        String p=c.path();boolean list=fieldPrefix(p)==null&&(p.startsWith("media.")||p.endsWith(".imageIds")||p.startsWith("markets.COUPANG.coupang."));
        if(!list){if(!value.isString()||value.asString().length()>10000)throw invalid("수정 입력의 형식을 확인해 주세요.");return;}
        if(!value.isArray()||value.size()>200)throw invalid("수정 목록은 200개 이하로 입력해 주세요.");
        Set<String> keys;
        if(p.equals("media.images"))keys=Set.of("id","assetId","url","representative","order","optionId","type");
        else if(p.equals("media.contents"))keys=Set.of("id","type","value","optionId");
        else if(p.endsWith(".registration")||p.endsWith(".settings")||p.endsWith(".delivery"))keys=Set.of("name","value");
        else if(p.endsWith(".attributes"))keys=Set.of("name","value","exposed");
        else if(p.endsWith(".notices"))keys=Set.of("category","name","content");
        else if(p.endsWith(".certifications"))keys=Set.of("type","code","attachments");
        else if(p.endsWith(".documents"))keys=Set.of("templateName","path","vendorPath");
        else {for(var row:value)if(!row.isString())throw invalid("이미지 식별자를 확인해 주세요.");return;}
        for(var row:value){if(!row.isObject())throw invalid("수정 목록 형식을 확인해 주세요.");for(var e:row.properties())if(!keys.contains(e.getKey()))throw invalid("지원하지 않는 수정 필드입니다.");
            for(var e:row.properties()){
                String key=e.getKey();var cell=e.getValue();
                if(key.equals("representative")){if(!cell.isBoolean())throw invalid("대표 이미지 선택값을 확인해 주세요.");}
                else if(key.equals("order")){if(!cell.isIntegralNumber()||cell.asLong()<0||cell.asLong()>200)throw invalid("이미지 순서를 확인해 주세요.");}
                else if(key.equals("attachments")){if(!cell.isArray()||cell.size()>100)throw invalid("인증 첨부 형식을 확인해 주세요.");for(var attachment:cell){if(!attachment.isObject())throw invalid("인증 첨부 형식을 확인해 주세요.");for(var part:attachment.properties())if(!Set.of("order","type","url").contains(part.getKey()))throw invalid("지원하지 않는 인증 첨부 필드입니다.");}}
                else if(!cell.isString()&&!cell.isNull())throw invalid("수정 입력의 형식을 확인해 주세요.");
            }
            if(c.optionId()!=null&&p.startsWith("media.")&&!Objects.equals(c.optionId(),text(row.path("optionId"))))throw invalid("이미지·설명의 옵션 연결을 확인해 주세요.");
        }
        if(json.writeValueAsBytes(value).length>5*1024*1024)throw invalid("수정 내용은 5MiB 이하로 입력해 주세요.");
    }
    private static void assign(ObjectNode root,MarketplaceEditing.Change c,JsonNode value,ObjectMapper json){
        String p=c.path();
        String field=fieldPrefix(p);if(field!=null){var base=new MarketplaceEditing.Change(field,c.optionId(),List.of());field(valueFrom(root,base),p.substring(field.length()+1),value,json);return;}
        if(p.startsWith("options.")){option(root.path("options"),c.optionId(),"id").set(p.substring(8),value);return;}
        if(p.startsWith("markets.COUPANG.coupang.options.")){option(root.path("markets").path("COUPANG").path("coupang").path("options"),c.optionId(),"optionId").set(p.substring("markets.COUPANG.coupang.options.".length()),value);return;}
        if(p.startsWith("media.")&&c.optionId()!=null){String key=p.substring(6);var rows=json.createArrayNode();for(var row:root.path("media").path(key))if(!Objects.equals(c.optionId(),text(row.path("optionId"))))rows.add(row);for(var row:value)rows.add(row);root.path("media").asObject().set(key,rows);return;}
        var parts=p.split("\\.");ObjectNode at=root;for(int n=0;n<parts.length-1;n++){var next=at.path(parts[n]);if(!next.isObject())throw invalid("수정할 마켓 정보를 확인해 주세요.");at=next.asObject();}at.set(parts[parts.length-1],value);
    }
    private static ObjectNode option(JsonNode rows,String id,String key){for(var row:rows)if(Objects.equals(id,text(row.path(key))))return row.asObject();throw invalid("상품 옵션 연결을 확인해 주세요.");}
    private static String fieldPrefix(String path){return FIELDS.stream().filter(p->path.startsWith(p+".")).findFirst().orElse(null);}
    private static ArrayNode valueFrom(JsonNode root,MarketplaceEditing.Change c){JsonNode at=root;if(c.path().endsWith(".registration"))return option(root.path("markets").path("COUPANG").path("coupang").path("options"),c.optionId(),"optionId").path("registration").asArray();for(var part:c.path().split("\\."))at=at.path(part);return at.asArray();}
    private static void aliases(ObjectNode root,MarketplaceEditing.Change c,JsonNode value,ObjectMapper json){
        var market=root.path("markets").path("COUPANG");var p=c.path();
        if(!market.isObject()||!market.path("coupang").isObject())return;
        if(!market.path("overrides").isObject())market.asObject().set("overrides",json.valueToTree(new Overrides(null,null,null,List.of(),null,null)));
        if(Set.of("common.name","common.brand").contains(p))market.path("overrides").asObject().set(p.substring(7),value);
        if(p.startsWith("options."))for(var row:market.path("overrides").path("options"))if(Objects.equals(c.optionId(),text(row.path("optionId"))))row.asObject().set(p.substring(8),value);
        if(p.equals("common.manufacturer"))field(market.path("coupang").path("settings").asArray(),"manufacture",value,json);
        if(p.startsWith("delivery.")){String key=DefaultCoupangWriteGateway.deliveryWire(p.substring(9));field(market.path("coupang").path("delivery").asArray(),key,value,json);}
        String key=Map.of("common.model","modelNo","common.taxType","taxType","common.adultOnly","adultOnly","options.sku","externalVendorSku").get(p);
        if(key!=null)for(var row:market.path("coupang").path("options"))if(c.optionId()==null||Objects.equals(c.optionId(),text(row.path("optionId"))))field(row.path("registration").asArray(),key,value,json);
    }
    private static void field(ArrayNode rows,String key,JsonNode value,ObjectMapper json){for(var row:rows)if(key.equals(text(row.path("name")))){row.asObject().set("value",value);return;}var next=json.createObjectNode();next.put("name",key);next.set("value",value);rows.add(next);}
    static JsonNode displayValue(JsonNode value,MarketplaceEditing.Change c){return c.path().startsWith("media.")?media(value):value;}
    private static JsonNode media(JsonNode value){var clone=value.deepCopy();if(clone.isArray())for(var row:clone)if(row.isObject())row.asObject().remove("id");return clone;}
    private static String number(JsonNode n){String value=text(n);if(value!=null&&value.matches("[0-9]{1,20}"))return new java.math.BigInteger(value).toString();return value;}
    private static List<String> searchTags(String value){return Arrays.stream(value.split(",")).map(String::trim).filter(tag->!tag.isEmpty()).toList();}
    private static String text(JsonNode n){return n.isString()?n.asString():null;}
    private static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
}
