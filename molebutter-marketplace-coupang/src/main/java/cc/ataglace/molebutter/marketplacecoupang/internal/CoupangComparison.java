package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.util.*;
import java.nio.charset.StandardCharsets;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;
import tools.jackson.databind.ObjectMapper;

/** Logical remote slots, independent of internal names, array order and source ownership. */
final class CoupangComparison {
    private static final String REG="markets.COUPANG.coupang.options.registration.";
    private static final Set<String> UNSUPPORTED=Set.of("options.name","common.origin","common.material","common.afterService");
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("originalPrice","정상가"),Map.entry("maximumBuyForPerson","인당 구매 제한"),Map.entry("maximumBuyForPersonPeriod","구매 제한 기간"),
        Map.entry("outboundShippingTimeDay","출고 소요일"),Map.entry("unitCount","판매 단위"),Map.entry("adultOnly","구매 연령"),Map.entry("taxType","과세 유형"),
        Map.entry("parallelImported","병행 수입"),Map.entry("overseasPurchased","해외 구매"),Map.entry("pccNeeded","개인통관부호 필수"),
        Map.entry("bestPriceGuaranteed3P","최저가 보장"),Map.entry("barcode","바코드"),Map.entry("emptyBarcode","바코드 없음"),
        Map.entry("emptyBarcodeReason","바코드 미등록 사유"),Map.entry("modelNo","모델번호"),Map.entry("searchTags","검색어"),
        Map.entry("offerCondition","상품 상태"),Map.entry("offerDescription","상태 설명"));
    static boolean supported(Change c){return !UNSUPPORTED.contains(c.path())&&!c.path().contains("autoPricingInfoView.");}
    static List<Change> fields(Document d,ObjectMapper json){
        var out=new ArrayList<Change>();for(var c:CoupangEditPatch.snapshot(d,json))if(supported(c)&&!c.path().startsWith("media.")&&!c.path().equals(REG+"originalPrice"))out.add(c);
        var m=d.markets().get(Market.COUPANG);if(m==null||m.coupang()==null)return List.copyOf(out);
        for(var o:d.options()){
            var normal=DraftCoupangProjection.registration(d,m,o).get("originalPrice");if(normal!=null&&!normal.isBlank())out.add(new Change(REG+"originalPrice",o.id(),normal));
            var images=DraftCoupangProjection.images(d,m,o).stream().sorted(Comparator.comparingInt(Image::order)).map(i->i.optionId()==null?new Image(stable(o.id(),i.id()),i.assetId(),i.url(),i.representative(),i.order(),o.id(),i.type(),i.id()):i).toList();
            out.add(new Change("media.images",o.id(),images));
            List<Content> values=m.overrides()!=null&&m.overrides().description()!=null?List.of(new Content(stable(o.id(),"description-override"),"HTML",m.overrides().description(),o.id())):MarketplaceDocuments.contentsFor(d,o.id()).stream().map(c->c.optionId()==null?new Content(stable(o.id(),c.id()),c.type(),c.value(),o.id(),c.assetId(),c.id()):c).toList();
            out.add(new Change("media.contents",o.id(),values));
        }return List.copyOf(out);
    }
    static List<ConflictKey> known(Document d,List<ConflictKey> old,Document transmitted,ObjectMapper json){
        var original=new HashSet<>(old);var out=new LinkedHashSet<ConflictKey>();
        var sent=new HashMap<ConflictKey,Change>();if(transmitted!=null)for(var c:fields(transmitted,json))sent.put(new ConflictKey(c.path(),c.optionId()),c);
        for(var c:fields(d,json)){var k=new ConflictKey(c.path(),c.optionId());
            boolean inherited=c.path().startsWith("media.")&&original.contains(new ConflictKey(c.path(),null));
            boolean normal=c.path().equals(REG+"originalPrice")&&sent.containsKey(k)&&Objects.equals(c.value(),sent.get(k).value());
            if(original.contains(k)||inherited||normal)out.add(k);
        }return List.copyOf(out);
    }
    static Object value(Change c,ObjectMapper json){
        if(c.path().endsWith(".attributes")){var out=new TreeMap<String,Object>();for(var v:json.valueToTree(c.value())){String key=v.path("name").asString()+" / "+v.path("exposed").asString();if(out.putIfAbsent(key,v.path("value").asString())!=null)throw new IllegalArgumentException("중복된 속성명·구분이 있습니다. 원본 속성을 확인해 주세요.");}return out;}
        return c.value();
    }
    static String label(Change c){if(c.path().startsWith(REG))return LABELS.getOrDefault(c.path().substring(REG.length()),c.path().substring(REG.length()));if(c.path().equals("media.images"))return "대표·추가 이미지";if(c.path().equals("media.contents"))return "상세설명";return null;}
    static String source(Document d,Change c){if(!c.path().startsWith("media."))return null;
        var common=new HashSet<String>();if(c.path().equals("media.images")){for(var i:d.media().images())if(i.optionId()==null)common.add(i.id());var own=d.media().images().stream().filter(i->Objects.equals(i.optionId(),c.optionId())).toList();return !common.isEmpty()&&(own.isEmpty()&&!Boolean.TRUE.equals(d.media().imageOverrides().get(c.optionId()))||!Boolean.TRUE.equals(d.media().imageOverrides().get(c.optionId()))&&!own.isEmpty()&&own.stream().allMatch(i->common.contains(i.sourceId())))?"COMMON":"OPTION";}
        for(var v:d.media().contents())if(v.optionId()==null)common.add(v.id());var own=d.media().contents().stream().filter(v->Objects.equals(v.optionId(),c.optionId())).toList();return !common.isEmpty()&&(own.isEmpty()&&!Boolean.TRUE.equals(d.media().contentOverrides().get(c.optionId()))||!Boolean.TRUE.equals(d.media().contentOverrides().get(c.optionId()))&&!own.isEmpty()&&own.stream().allMatch(v->common.contains(v.sourceId())))?"COMMON":"OPTION";
    }
    static Object display(Change c,ObjectMapper json){String s=Objects.toString(c.value(),"");if(c.path().startsWith(REG)){
        if(s.equals("true"))return "예";if(s.equals("false"))return "아니요";
        if(c.path().equals(REG+"originalPrice")&&s.matches("[0-9]+"))return String.format(java.util.Locale.KOREA,"%,d원",new java.math.BigInteger(s));
        return Map.ofEntries(Map.entry("NEW","새 상품"),Map.entry("REFURBISHED","리퍼"),Map.entry("USED_BEST","중고(최상)"),Map.entry("USED_GOOD","중고(상)"),Map.entry("USED_NORMAL","중고(중)"),Map.entry("EVERYONE","전체 연령"),Map.entry("ADULT_ONLY","성인 전용"),Map.entry("TAX","과세"),Map.entry("FREE","면세")).getOrDefault(s,s);
    }return value(c,json);}
    private static String stable(String option,String source){return UUID.nameUUIDFromBytes((option+"|comparison|"+source).getBytes(StandardCharsets.UTF_8)).toString();}
}
