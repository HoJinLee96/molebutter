package cc.ataglace.molebutter.procurement.api;


import java.util.*;
import java.util.regex.*;
import cc.ataglace.molebutter.procurement.api.ProductDtos.CodeMatch;

/** Excel initial-query generation and legacy response compatibility only; never filters search results. */
public final class ProductCodePolicy {
    public static final String ACCESSORY="LF_ACCESSORY", GENERAL="GENERAL";
    private static final Pattern FULL=Pattern.compile("^([A-Z]{4})([0-9][EF])([0-9]{3})([A-Z][A-Z0-9])$");
    private static final Map<String,List<String>> BRANDS=Map.of("DAKS",List.of("닥스","DAKS"),"HAZZYS",List.of("헤지스","HAZZYS"),"JILLSTUART",List.of("질스튜어트","JILLSTUART","JILL STUART"));
    private ProductCodePolicy() {}
    public static String normalize(String code){return code==null?"":code.trim().toUpperCase(Locale.ROOT);}
    public static String brandKey(String name){String s=normalize(name);return BRANDS.entrySet().stream().filter(e->e.getValue().contains(s)).map(Map.Entry::getKey).findFirst().orElse("");}
    public static String comparison(String type,String code) {
        if(!ACCESSORY.equals(type))return "";
        var m=FULL.matcher(normalize(code));return m.matches()?m.group(1)+m.group(3):"";
    }
    public static String suggested(String type,String code){String key=comparison(type,code);return key.isBlank()?normalize(code):key;}
    public static CodeMatch review(String message){return new CodeMatch("REVIEW",null,null,null,null,message);}
}
