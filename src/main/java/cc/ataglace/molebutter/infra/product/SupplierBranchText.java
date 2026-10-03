package cc.ataglace.molebutter.infra.product;

import java.util.*;
import java.util.regex.*;
import java.text.Normalizer;
import org.springframework.web.util.HtmlUtils;

public final class SupplierBranchText {
    private SupplierBranchText() {}
    private static final Pattern TOKEN=Pattern.compile("(?<![가-힣A-Za-z0-9])([가-힣A-Za-z][가-힣A-Za-z0-9]{0,19}점)(?![가-힣A-Za-z0-9])");
    private static final Pattern RETAILER=Pattern.compile("현대백화점|롯데백화점|신세계백화점|갤러리아백화점|AK플라자");
    private static final Set<String> GENERIC=Set.of("백화점","판매점","전문점","대리점","가맹점","지점","장점","단점","관점","시점","만점","평점","강점","특장점","면세점","입점");
    public static String clean(String text){return Normalizer.normalize(HtmlUtils.htmlUnescape(Objects.toString(text,"").replaceAll("<[^>]*>"," ")),Normalizer.Form.NFC).trim();}
    public static Set<String> retailers(String text){var names=new LinkedHashSet<String>();var m=RETAILER.matcher(clean(text));while(m.find())names.add(m.group());return names;}
    public static Set<String> names(String text){var names=new LinkedHashSet<String>();String s=RETAILER.matcher(clean(text)).replaceAll(" ").replaceAll("(?:에이케이)?백화점"," ");var m=TOKEN.matcher(s);while(m.find())if(!GENERIC.contains(m.group(1)))names.add(m.group(1));return names;}
}
