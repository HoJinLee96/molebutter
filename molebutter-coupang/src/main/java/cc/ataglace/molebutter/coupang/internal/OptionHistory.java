package cc.ataglace.molebutter.coupang.internal;

import java.util.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.*;

/** Read-only scalar projection. Never exposes unrelated saved documents or request bodies. */
final class OptionHistory {
    static List<OptionEvent> project(ObjectMapper json,List<Event> events,String vendor,String option,String copy,String kind) {
        String field=kind.equals("PRICE")?"salePrice":"amountInStock";
        String registration=kind.equals("PRICE")?"salePrice":"maximumBuyCount";
        var out=new ArrayList<OptionEvent>();var requests=new HashMap<String,OptionEvent>();
        for(var event:events) {
            Long before=null,after=null;boolean include=false;
            if(event.kind().equals("INVENTORY")&&vendor.equals(event.subject())) {
                if(event.path().equals("$/"+field)||event.path().equals("$/data/"+field)) {
                    before=number(tree(json,event.beforeJson()));after=number(tree(json,event.afterJson()));include=true;
                }else if(Set.of("$","$/data").contains(event.path())) {
                    before=inventory(tree(json,event.beforeJson()),field);after=inventory(tree(json,event.afterJson()),field);include=true;
                }
            }else if(option!=null&&event.kind().equals("WORKING_COPY_SAVE")&&Objects.equals(copy,event.subject())) {
                before=saved(tree(json,event.beforeJson()),option,registration);after=saved(tree(json,event.afterJson()),option,registration);include=true;
            }else if(option!=null&&option.equals(event.subject())&&kind.equals(event.path())&&event.kind().startsWith("EXECUTION_")) {
                if(event.kind().equals("EXECUTION_REQUEST")) {
                    before=inventory(tree(json,event.beforeJson()),field);after=inventory(tree(json,event.afterJson()),field);
                }else {
                    var request=requests.get(event.executionId());
                    if(request!=null){before=request.before();after=request.after();}
                }
                include=true;
            }
            if(!include)continue;
            boolean execution=event.kind().startsWith("EXECUTION_");
            if(!execution&&(Objects.equals(before,after)||before==null&&after==null))continue;
            var item=new OptionEvent(event.id(),event.kind(),before,after,event.actor(),event.executionId(),event.createdAt());
            out.add(item);
            if(event.kind().equals("EXECUTION_REQUEST")&&event.executionId()!=null)requests.put(event.executionId(),item);
        }
        Collections.reverse(out);return List.copyOf(out);
    }
    private static JsonNode tree(ObjectMapper json,String value){return value==null?json.missingNode():json.readTree(value);}
    private static Long number(JsonNode n) {
        if(n==null||n.isMissingNode()||n.isNull())return null;
        if(!n.isIntegralNumber()&&!n.isString())return null;
        try{return Long.valueOf(n.asString());}catch(NumberFormatException invalid){return null;}
    }
    private static Long inventory(JsonNode n,String field){return number(n.has("data")?n.path("data").path(field):n.path(field));}
    private static Long saved(JsonNode n,String option,String field){
        var matches=new ArrayList<JsonNode>();for(var row:n.path("options"))if(option.equals(row.path("id").asString()))matches.add(row);
        return matches.size()==1?number(matches.getFirst().path("registration").path(field)):null;
    }
}
