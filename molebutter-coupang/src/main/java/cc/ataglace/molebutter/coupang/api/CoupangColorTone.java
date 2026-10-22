package cc.ataglace.molebutter.coupang.api;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed search-filter vocabulary; this is distinct from an option's purchase color. */
public final class CoupangColorTone {
    private CoupangColorTone() {}
    public static final List<String> VALUES=List.of("그린 톤","다색","레드 톤","메탈릭 톤","밝은 톤","브라운 톤","블랙 톤","블루 톤","어스 톤","중성 톤","파스텔 톤","핑크 톤","화이트/오프 화이트 톤","회색 톤");
    private static final List<String> WORDS=List.of("그린|녹색|초록색?","다색|멀티\\s*컬러|멀티\\s*색상|배색","레드|빨강|빨간색|적색|버건디|와인색","메탈릭|실버|골드|은색|금색","밝은\\s*톤","(?:다크|라이트|딥)?브라운|갈색","블랙|검정색?|검은색|흑색","블루|네이비|파랑|파란색|청색|남색","어스\\s*톤","중성\\s*톤","파스텔\\s*톤","핑크|분홍색?","화이트|오프\\s*화이트|흰색|백색|아이보리","그레이|회색|차콜");
    public static boolean attribute(String name,String exposed){return "NONE".equals(exposed)&&"패션의류잡화색상계열".equals(Objects.toString(name,"").replaceAll("[^가-힣A-Za-z0-9]",""));}
    public static String suggest(String... titles){
        for(String source:titles){String title=Normalizer.normalize(Objects.toString(source,""),Normalizer.Form.NFKC);var found=new LinkedHashSet<String>();
            for(int i=0;i<WORDS.size();i++)if(Pattern.compile("(?<![가-힣A-Za-z])(?:"+WORDS.get(i)+")(?:\\s*톤)?(?![가-힣A-Za-z])",Pattern.CASE_INSENSITIVE).matcher(title).find())found.add(VALUES.get(i));
            if(found.contains("다색"))return "다색";
            if(found.size()>1)return null;
            if(found.size()==1)return found.iterator().next();
        }return null;
    }
}
