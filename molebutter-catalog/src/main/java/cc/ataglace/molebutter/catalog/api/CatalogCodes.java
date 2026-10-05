package cc.ataglace.molebutter.catalog.api;

import java.util.*;
public final class CatalogCodes {
    private CatalogCodes() {}
    public static String normalize(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT);}
    public static String brandKey(String name){return switch(normalize(name).replace(" ","")){case "닥스","DAKS"->"DAKS";case "헤지스","HAZZYS"->"HAZZYS";case "질스튜어트","JILLSTUART"->"JILLSTUART";default->"";};}
}
