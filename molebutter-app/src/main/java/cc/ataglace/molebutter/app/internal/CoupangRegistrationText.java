package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.imaging.api.ProductLookupDto;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic registration text from explicit source facts, never from photos or SKU inference. */
final class CoupangRegistrationText {
    static cc.ataglace.molebutter.coupang.api.CoupangSearchTags.Context searchContext(ProductLookupDto p) {
        String raw=clean(p.sourceProductName());var evidence=new ArrayList<String>();evidence.add(raw);evidence.addAll(p.categoryNames());
        var materials=new ArrayList<String>();materials.add(raw);var colors=new LinkedHashSet<String>();
        p.notificationFields().forEach((k,v)->{
            String key=label(k);
            if(Set.of("성별","사용대상","대상","종류","특징","디자인").contains(key))evidence.add(v);
            if(Set.of("소재","제품소재","제품소재제품주소재","제품주소재","재질").contains(key))materials.add(v);
            if(Set.of("색상","컬러","제품색상").contains(key))for(String color:clean(v).split("[,/;|]"))if(!color.isBlank()&&color.length()<=100&&!color.matches(".*(?:상세.*페이지|참조|해당.*없음|선택).*"))colors.add(color);
        });
        var m=Pattern.compile("(?<![가-힣A-Za-z])(블랙|검정|검은색|화이트|흰색|네이비|브라운|베이지|그레이|회색|아이보리|카키|그린|녹색|레드|빨강|블루|파랑|핑크|옐로우|노랑|오렌지|퍼플|실버|골드)(?![가-힣A-Za-z])").matcher(raw);
        if(colors.isEmpty())while(m.find())colors.add(m.group());
        String facts=clean(String.join(" ",evidence)),material=clean(String.join(" ",materials));
        return new cc.ataglace.molebutter.coupang.api.CoupangSearchTags.Context(facts.substring(0,Math.min(facts.length(),200)),material.substring(0,Math.min(material.length(),500)),facts.substring(0,Math.min(facts.length(),500)),colors.stream().limit(32).toList());
    }
    record Title(String display, String general, List<String> missing) {}
    private static String clean(String s) {
        return Normalizer.normalize(Objects.toString(s,""),Normalizer.Form.NFKC).replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
    }
    private static String label(String s) { return clean(s).replaceAll("[^가-힣A-Za-z0-9]", ""); }
    static boolean manufacturerField(String name) {
        return Set.of("제조사","제조자","제조업체","제조자수입자","제조사수입자","제조자수입자명",
                "제조자공식수입병행수입","제조사공식수입병행수입").contains(label(name));
    }
    static List<String> manufacturers(ProductLookupDto p) {
        return p.notificationFields().entrySet().stream().filter(e->manufacturerField(e.getKey()))
                .map(e->clean(e.getValue())).filter(v->!v.isEmpty()).distinct().toList();
    }
    static Title title(ProductLookupDto p) {
        // Older observations have no raw name: do not recycle their inferred/display gender.
        String raw=clean(p.sourceProductName());
        if(p.productCode()!=null&&!p.productCode().isBlank())raw=clean(raw.replaceAll("(?i)(?<![A-Z0-9])"+Pattern.quote(p.productCode())+"(?![A-Z0-9])"," "));
        var explicit=new ArrayList<String>();explicit.add(raw);explicit.addAll(p.categoryNames());
        p.notificationFields().forEach((k,v)->{if(Set.of("성별","사용대상","대상","종류","특징","디자인").contains(label(k)))explicit.add(v);});
        String evidence=clean(String.join(" ",explicit));
        boolean male=Pattern.compile("남성|남자|남아|\\bMEN(?:'S)?\\b",Pattern.CASE_INSENSITIVE).matcher(evidence).find();
        boolean female=Pattern.compile("여성|여자|여아|\\bWOMEN(?:'S)?\\b",Pattern.CASE_INSENSITIVE).matcher(evidence).find();
        String gender=male!=female?(male?"남성":"여성"):"";
        String materialEvidence=clean(p.notificationFields().entrySet().stream()
                .filter(e->Set.of("소재","제품소재","제품소재제품주소재","제품주소재","재질").contains(label(e.getKey())))
                .map(Map.Entry::getValue).reduce(raw,(a,b)->a+" "+b));
        String material="";
        for(var rule:List.of(new String[]{"천연\\s*(?:소가죽|가죽\\s*\\(\\s*소\\s*\\))","천연 소가죽"},
                new String[]{"소가죽|우피","소가죽"},new String[]{"합성\\s*가죽|인조\\s*가죽","합성가죽"},
                new String[]{"천연\\s*가죽","천연가죽"},new String[]{"가죽","가죽"},
                new String[]{"나일론|NYLON","나일론"},new String[]{"캔버스|CANVAS","캔버스"},
                new String[]{"폴리에스터|POLYESTER","폴리에스터"},new String[]{"(?:^|[\\s:;,(])면(?:[\\s0-9%/,)]|$)|COTTON","면"})) {
            if(Pattern.compile(rule[0],Pattern.CASE_INSENSITIVE).matcher(materialEvidence).find()){material=rule[1];break;}
        }
        var features=new LinkedHashSet<String>();
        for(var rule:List.of(new String[]{"체크(?:\\s*패턴)?","체크 패턴"},new String[]{"스트라이프","스트라이프"},
                new String[]{"(?<![0-9])2\\s*단","2단"},new String[]{"(?<![0-9])3\\s*단","3단"},new String[]{"참\\s*장식","참장식"},
                new String[]{"퀼팅","퀼팅"},new String[]{"로고","로고"}))
            if(Pattern.compile(rule[0]).matcher(evidence).find())features.add(rule[1]);
        String category="";
        // Prefer the concrete source name over a parent category; never guess an absent category.
        String specific="카드\\s*지갑|반지갑|중지갑|장지갑|크로스\\s*백|숄더\\s*백|토트\\s*백|백팩|클러치|벨트";
        for(String pattern:List.of(specific,"지갑|가방")) {
            for(String text:List.of(raw,evidence)) {
                var match=Pattern.compile(pattern).matcher(text);
                if(match.find()){category=match.group().replaceAll("\\s+","");break;}
            }
            if(!category.isBlank())break;
        }
        var missing=new ArrayList<String>();var parts=new ArrayList<String>();
        for(var entry:List.of(Map.entry("성별",gender),Map.entry("소재",material),Map.entry("특징",String.join(" ",features)),Map.entry("카테고리",category))) {
            if(entry.getValue().isBlank())missing.add(entry.getKey());else parts.add(entry.getValue());
        }
        String general=String.join(" ",parts),brand=clean(p.brandName());
        return new Title(clean(brand+" "+general),general,List.copyOf(missing));
    }
}
