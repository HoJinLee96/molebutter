package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

/** Keeps provider input and local UUIDs; removes all old provider identity and approval state. */
final class RecreationDocuments {
    static Document clean(Document desired,String market,ObjectMapper json){
        var stored=desired.markets().get(Market.valueOf(market));
        if(stored==null||market.equals("COUPANG")&&stored.coupang()==null||market.equals("NAVER")&&(stored.naver()==null||stored.naver().editorInput()==null))throw new cc.ataglace.molebutter.common.api.InputValidationFailure("마지막 마켓 입력이 보관되어 있지 않아 재등록 준비를 할 수 없습니다. 공통 기준에서 새 등록을 준비해 주세요.");
        var tree=json.valueToTree(desired).asObject();var config=tree.path("markets").path(market).asObject();
        if(market.equals("COUPANG")){
            var cp=config.path("coupang").asObject();cp.putNull("sellerProductId");cp.putNull("source");
            for(var row:cp.path("options")){row.asObject().putNull("sellerProductItemId");row.asObject().putNull("vendorItemId");}
            var settings=cp.putArray("settings");for(var field:desired.markets().get(Market.COUPANG).coupang().settings())if(!Set.of("vendorUserId","requested").contains(field.name()))settings.add(json.valueToTree(field));
        }else if(market.equals("NAVER")){
            var n=config.path("naver").asObject();n.putNull("channelId");var input=n.path("editorInput");input.path("fields").asObject().put("originProduct.statusType","SALE");
            var overrides=new ArrayList<String>();input.path("fields").properties().forEach(e->overrides.add("fields."+e.getKey()));
            var state=new EditorState(1,List.copyOf(overrides),"CUSTOM","CUSTOM","CUSTOM",null,"CUSTOM",null,List.of());n.set("editorState",json.valueToTree(state));
        }else throw new cc.ataglace.molebutter.common.api.InputValidationFailure("이 마켓은 삭제 확정 후 재등록 전환을 아직 지원하지 않습니다.");
        return MarketplaceDocuments.normalize(json.treeToValue(tree,Document.class));
    }
    static Document reference(Document reference,Document clean,String market){
        var configs=new EnumMap<Market,MarketConfig>(Market.class);configs.putAll(reference.markets());configs.put(Market.valueOf(market),clean.markets().get(Market.valueOf(market)));
        return new Document(reference.id(),reference.revision(),reference.common(),reference.options(),reference.stockMode(),reference.productQuantity(),reference.services(),reference.media(),reference.delivery(),reference.selectedMarkets(),Map.copyOf(configs));
    }
}
