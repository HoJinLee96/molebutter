package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;

/** Only typed provider projections participate; neither browser values nor raw responses are authority. */
final class TargetComparisons {
    private static final ObjectMapper JSON=new ObjectMapper();
    static ConflictKey key(Change c){return new ConflictKey(c.path(),c.optionId());}
    static Map<ConflictKey,Change> fields(MarketplaceProductAdapter a,Document d){var out=new LinkedHashMap<ConflictKey,Change>();if(d!=null)for(var c:a.comparisonFields(d))out.put(key(c),c);return out;}
    static Map<ConflictKey,Change> observedFields(MarketplaceProductAdapter a,Document d){var out=new LinkedHashMap<ConflictKey,Change>();if(d!=null)for(var c:a.observedComparisonFields(d))out.put(key(c),c);return out;}
    static boolean equal(Object a,Object b){return equivalent(JSON.valueToTree(a),JSON.valueToTree(b));}
    private static boolean equivalent(tools.jackson.databind.JsonNode a,tools.jackson.databind.JsonNode b){
        if(a.isNumber()&&b.isNumber())return new java.math.BigDecimal(a.asString()).compareTo(new java.math.BigDecimal(b.asString()))==0;
        if(a.isArray()&&b.isArray()){if(a.size()!=b.size())return false;for(int n=0;n<a.size();n++)if(!equivalent(a.get(n),b.get(n)))return false;return true;}
        if(a.isObject()&&b.isObject()){if(a.size()!=b.size())return false;for(var e:a.properties())if(!b.has(e.getKey())||!equivalent(e.getValue(),b.path(e.getKey())))return false;return true;}return a.equals(b);
    }
    static String hash(Object value){return MarketplaceHashes.sha256(JSON.writeValueAsString(value));}
    static final int POLICY_VERSION=2;
    static Comparison compare(MarketplaceProductAdapter adapter,Target target,TargetEditStore.SavedTarget saved,long revision,long generation){return compare(adapter,target,saved,revision,generation,Map.of());}
    static Comparison compare(MarketplaceProductAdapter adapter,Target target,TargetEditStore.SavedTarget saved,long revision,long generation,Map<ConflictKey,ComparisonMedia.Decision> media){
        if(!Set.of("READY","UNSUPPORTED").contains(target.status()))return new Comparison(hash(Arrays.asList(POLICY_VERSION,target.market(),revision,generation,target.status())),saved==null?null:saved.updatedAt(),List.of());
        var now=observedFields(adapter,target.document());var app=saved==null?Map.<ConflictKey,Change>of():fields(adapter,saved.document());
        var known=saved==null?Set.<ConflictKey>of():new HashSet<>(saved.known());
        var keys=new LinkedHashSet<>(now.keySet());keys.addAll(known);var groups=new LinkedHashMap<String,List<ConflictKey>>();
        for(var k:keys){var field=now.containsKey(k)?now.get(k):app.get(k);if(field==null||!adapter.comparisonSupported(field))continue;String group=brand(k.path())?"brand":k.path()+"|"+Objects.toString(k.optionId(),"");groups.computeIfAbsent(group,x->new ArrayList<>()).add(k);}
        var rows=new ArrayList<ComparisonRow>();
        for(var e:groups.entrySet()){
            var ks=e.getValue();boolean observed=ks.stream().allMatch(now::containsKey),stored=ks.stream().allMatch(k->known.contains(k)&&app.containsKey(k));
            Object av=value(ks,now),bv=value(ks,app);if(!observed&&!stored)continue;if(!stored&&empty(av))continue;
            String issue=null;for(var k:ks){if(now.containsKey(k))issue=adapter.comparisonIssue(target.document(),now.get(k));if(issue==null&&stored)issue=adapter.comparisonIssue(saved.document(),app.get(k));if(issue!=null)break;}
            var evidence=ks.size()==1?media.get(ks.getFirst()):null;boolean mediaField=target.market().equals("COUPANG")&&ks.getFirst().path().startsWith("media.");
            if(stored&&observed&&issue==null){if(mediaField){if(evidence!=null&&evidence.state().equals("SAME"))continue;}else if(equal(logical(adapter,target.document(),ks,now),logical(adapter,saved.document(),ks,app)))continue;}
            boolean unverified=issue!=null||!observed||stored&&mediaField&&(evidence==null||evidence.state().equals("UNVERIFIED"));
            String replayReason=adapter.replayReason(target.document(),ks.stream().map(k->app.containsKey(k)?app.get(k):now.get(k)).filter(Objects::nonNull).toList());
            String reason=issue!=null?issue:!observed?"마켓 응답에서 확인되지 않은 항목입니다.":unverified?"이미지 동일성을 확인하지 못했습니다. 파일 접근 또는 변환 결과를 확인해 주세요.":!stored?"마켓 기본값 · 앱 저장 의도 없음":replayReason;
            boolean writable="READY".equals(target.status())&&"UPDATE".equals(target.mode())&&replayReason==null&&!unverified;
            if(writable&&stored)try{adapter.apply(target.document(),ks.stream().map(app::get).toList());}catch(RuntimeException invalid){writable=false;reason=invalid.getMessage();}
            var first=now.containsKey(ks.getFirst())?now.get(ks.getFirst()):app.get(ks.getFirst());
            rows.add(new ComparisonRow(e.getKey(),List.copyOf(ks),av,bv,observed,stored,observed&&!unverified,observed&&stored&&writable,reason,
                unverified?"UNVERIFIED":stored?"DIFFERENCE":"INFORMATION",adapter.comparisonLabel(first),saved==null?null:adapter.comparisonSource(saved.document(),first),
                issue==null?display(adapter,target.document(),ks,now):av,issue==null&&saved!=null?display(adapter,saved.document(),ks,app):bv,evidence==null?null:evidence.token()));
        }
        String token=hash(Arrays.asList(POLICY_VERSION,target.market(),revision,generation,now,saved==null?null:saved.document(),saved==null?null:saved.known(),media));
        return new Comparison(token,saved==null?null:saved.updatedAt(),List.copyOf(rows),POLICY_VERSION);
    }
    static boolean sameField(MarketplaceProductAdapter a,Document before,Change x,Document after,Change y){return x!=null&&y!=null&&Objects.equals(x.path(),y.path())&&Objects.equals(x.optionId(),y.optionId())&&equal(a.comparisonValue(before,x),a.comparisonValue(after,y));}
    private static boolean empty(Object value){if(value==null)return true;if(value instanceof String s)return s.isBlank();if(value instanceof Collection<?> c)return c.isEmpty();if(value instanceof Map<?,?> m)return m.values().stream().allMatch(TargetComparisons::empty);return false;}
    private static Object logical(MarketplaceProductAdapter a,Document d,List<ConflictKey> keys,Map<ConflictKey,Change> fields){return transform(keys,fields,c->a.comparisonValue(d,c));}
    private static Object display(MarketplaceProductAdapter a,Document d,List<ConflictKey> keys,Map<ConflictKey,Change> fields){return transform(keys,fields,c->a.comparisonDisplay(d,c));}
    private static Object value(List<ConflictKey> keys,Map<ConflictKey,Change> fields){return transform(keys,fields,Change::value);}
    private static Object transform(List<ConflictKey> keys,Map<ConflictKey,Change> fields,java.util.function.Function<Change,Object> f){if(keys.size()==1)return fields.containsKey(keys.getFirst())?f.apply(fields.get(keys.getFirst())):null;var out=new LinkedHashMap<String,Object>();for(var key:keys)out.put(key.path(),fields.containsKey(key)?f.apply(fields.get(key)):null);return out;}
    private static boolean brand(String path){return Set.of("common.brand","markets.COUPANG.coupang.settings.brandId","markets.NAVER.naver.editorInput.fields.originProduct.detailAttribute.naverShoppingSearchInfo.brandId","markets.NAVER.naver.editorInput.fields.originProduct.detailAttribute.naverShoppingSearchInfo.brandName","partner.fields.brand","partner.fields.apiPrdAttrBrandCd").contains(path);}
}
