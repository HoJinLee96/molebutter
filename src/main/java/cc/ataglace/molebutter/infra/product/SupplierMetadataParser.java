package cc.ataglace.molebutter.infra.product;

import java.util.*;
import tools.jackson.databind.JsonNode;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import static cc.ataglace.molebutter.infra.product.NaverSearchPayload.text;

/** 상품 ID로 한정한 매장 메타데이터. 재고 유무나 추천 상품 데이터에 의존하지 않는다. */
final class SupplierMetadataParser {
    static StoreEvidence parse(Mall mall,List<JsonNode> roots,String id){
        var found=new LinkedHashSet<StoreEvidence>();
        for(var root:roots){
            if(mall==Mall.HI_THEHYUNDAI)hi(root,id,0,found);
            if(mall==Mall.LOTTE_ON&&LotteProductPayload.matches(root,id)){
                var evidence=lotte(root.path("data"));if(evidence!=null)found.add(evidence);
            }
            if(mall==Mall.NAVER_SMART_STORE){
                if(!naverMatches(root,id))continue;
                var channel=root.path("channel");var nestedChannel=root.path("contents").path("channel");
                String outerUid=text(channel,"channelUid"),innerUid=text(nestedChannel,"channelUid");
                String outerId=text(channel,"id"),innerId=text(nestedChannel,"id");
                if(!outerId.isBlank()&&!innerId.isBlank()&&!outerId.equals(innerId)){found.add(new StoreEvidence("CONFLICT",null,"판매채널 번호 불일치",null,null));continue;}
                if(!outerUid.isBlank()&&!innerUid.isBlank()&&!outerUid.equals(innerUid)){found.add(new StoreEvidence("CONFLICT",null,"판매채널 식별자 불일치",null,null));continue;}
                var evidence=naverStore(channel,nestedChannel);
                if(evidence!=null)found.add(evidence);
            }
        }
        if(found.isEmpty())return null;
        if(found.size()==1)return found.iterator().next();
        // 서로 다른 본상품 응답을 임의로 하나 선택하지 않는다.
        return new StoreEvidence("CONFLICT",null,String.join(" / ",found.stream().map(StoreEvidence::name).toList()),null,null);
    }
    private static StoreEvidence naverStore(JsonNode outer,JsonNode inner){
        var a=naverChannel(outer);var b=naverChannel(inner);
        if(a==null)return b;if(b==null)return a;
        if(!a.kind().equals(b.kind())||different(retailerKey(a.retailer()),retailerKey(b.retailer()))
                ||different(storeName(a),storeName(b))||differentIdentity(a.namespace(),b.namespace())
                ||differentIdentity(a.externalId(),b.externalId()))
            return new StoreEvidence("CONFLICT",null,a.name()+" / "+b.name(),null,null);
        // 한쪽에 없는 외부 식별자는 일치하는 다른 쪽 근거로 보완한다.
        var chosen=a.externalId()!=null?a:b;
        var refs=new LinkedHashMap<String,String>();
        for(var evidence:List.of(a,b))evidence.references().forEach((key,value)->{if(!value.isBlank())refs.put(key,value);});
        return new StoreEvidence(chosen.kind(),a.retailer()==null||a.retailer().isBlank()?b.retailer():a.retailer(),
                chosen.name(),chosen.namespace(),chosen.externalId(),refs);
    }
    private static StoreEvidence naverChannel(JsonNode channel){
        if(!channel.isObject())return null;
        var category=channel.path("storeCategory");
        var refs=Map.of("channelId",text(channel,"id"),"channelUid",text(channel,"channelUid"));
        var names=category.path("wholeNames");var ids=category.path("wholeIds");
        if(names.isArray()&&names.size()==2&&!names.path(0).asText("").isBlank()&&SupplierBranchText.names(names.path(1).asText("")).size()==1){
            String external=ids.isArray()&&ids.size()==2&&!ids.path(0).asText("").isBlank()&&!ids.path(1).asText("").isBlank()?ids.path(0).asText("")+"/"+ids.path(1).asText(""):null;
            return new StoreEvidence("BRANCH",names.path(0).asText(),names.path(1).asText(),external==null?null:"NAVER_DEPARTMENT",external,refs);
        }
        String exposure=text(category,"exposureText");var branches=SupplierBranchText.names(exposure);var retailers=SupplierBranchText.retailers(exposure);
        if(branches.size()==1&&retailers.size()==1)return new StoreEvidence("BRANCH",retailers.iterator().next(),branches.iterator().next(),null,null,refs);
        if(!"DEPARTMENT".equals(text(channel,"verticalType"))){
            String uid=text(channel,"channelUid"),name=text(channel,"channelName","name");
            if(!uid.isBlank()&&!name.isBlank())return new StoreEvidence("SELLER",null,name,"NAVER_CHANNEL",uid,refs);
        }
        return null;
    }
    private static String storeName(StoreEvidence evidence){
        var branches="BRANCH".equals(evidence.kind())?SupplierBranchText.names(evidence.name()):Set.<String>of();
        return branches.size()==1?branches.iterator().next():evidence.name();
    }
    private static String retailerKey(String value){
        var retailers=SupplierBranchText.retailers(value);
        return retailers.size()==1?retailers.iterator().next():value;
    }
    private static boolean differentIdentity(String a,String b){
        return a!=null&&b!=null&&!a.isBlank()&&!b.isBlank()&&!a.equals(b);
    }
    private static boolean different(String a,String b){
        if(a==null||b==null||a.isBlank()||b.isBlank())return false;
        return !a.trim().replaceAll("\\s+"," ").equalsIgnoreCase(b.trim().replaceAll("\\s+"," "));
    }
    private static StoreEvidence lotte(JsonNode data){
        var basic=data.path("basicInfo");var seller=data.path("slrInfo").path("trBase");
        var companies=values(basic,seller,"trNm");var ids=values(basic,seller,"trNo");
        var refs=new LinkedHashMap<String,String>();
        for(String field:List.of("trNo","trNm","lrtrNo","lrtrNm","trGrpNm")){
            refs.put("basic"+field,basic.path(field).asText(""));refs.put("seller"+field,seller.path(field).asText(""));
        }
        refs.put("trNo",text(basic,"trNo").isBlank()?text(seller,"trNo"):text(basic,"trNo"));
        refs.put("lrtrNo",text(basic,"lrtrNo").isBlank()?text(seller,"lrtrNo"):text(basic,"lrtrNo"));
        if(companies.size()>1||ids.size()>1)return new StoreEvidence("CONFLICT",null,String.join(" / ",companies),null,null,refs);
        var names=new LinkedHashSet<String>();var retailers=new LinkedHashSet<String>();
        for(var node:List.of(basic,seller)){
            for(String field:List.of("lrtrNm","trNm"))names.addAll(SupplierBranchText.names(text(node,field)));
            for(String field:List.of("lrtrNm","trNm","trGrpNm"))retailers.addAll(SupplierBranchText.retailers(text(node,field)));
        }
        if(retailers.size()>1)return new StoreEvidence("CONFLICT",null,String.join(" / ",retailers)+" "+String.join(" / ",names),null,null,refs);
        if(companies.contains("주식회사 LF")){
            if(!names.isEmpty()||!retailers.isEmpty())return new StoreEvidence("CONFLICT",null,"주식회사 LF / 백화점 지점",null,null,refs);
            if(ids.isEmpty())return null;
            return new StoreEvidence("COMPANY",null,"주식회사 LF","LOTTE_COMPANY",ids.iterator().next(),refs);
        }
        if(!names.isEmpty())return new StoreEvidence("BRANCH",String.join(" / ",retailers),String.join(" / ",names),null,null,refs);
        // 백화점이지만 지점이 없는 경우에는 일반 업체로 확정하지 않는다.
        if(!retailers.isEmpty()||companies.isEmpty()||ids.isEmpty())return null;
        return new StoreEvidence("SELLER",null,companies.iterator().next(),"LOTTE_COMPANY",ids.iterator().next(),refs);
    }
    private static Set<String> values(JsonNode basic,JsonNode seller,String field){
        var result=new LinkedHashSet<String>();
        for(var node:List.of(basic,seller)){String value=text(node,field).trim().replaceAll("\\s+"," ");if(!value.isBlank())result.add(value);}
        return result;
    }
    static boolean naverMatches(JsonNode root,String id){
        var values=List.of(text(root,"_id"),text(root,"id"),text(root,"channelProductNo"),text(root.path("contents"),"id"),text(root.path("contents"),"channelProductNo")).stream().filter(v->!v.isBlank()).toList();
        return !values.isEmpty()&&values.stream().allMatch(id::equals);
    }
    private static void hi(JsonNode n,String id,int depth,Set<StoreEvidence> out){
        if(depth>18)return;
        if(n.isObject()&&n.has("slitmCd")){
            if(!id.equals(text(n,"slitmCd")))return;
            String name=text(n,"storeNm"),code=text(n,"storeCd");
            if(!name.isBlank())out.add(new StoreEvidence("BRANCH","현대백화점",name,code.isBlank()?null:"HI_STORE",code.isBlank()?null:code));
            // 본상품 내부의 추천·동일 상품 목록은 매장 근거가 아니다.
            return;
        }
        for(var child:n)if(child.isObject()||child.isArray())hi(child,id,depth+1,out);
    }
}
