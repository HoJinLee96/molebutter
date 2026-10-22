package cc.ataglace.molebutter.coupang.api;

/** Formatting only: currency symbols, signs, decimals and invalid values remain subject to validation. */
public final class CoupangPrices {
    private CoupangPrices() {}
    public static String normalize(String value) { return value==null?null:value.trim().replace(",",""); }
    public static java.util.List<CoupangEditing.Change> changes(java.util.List<CoupangEditing.Change> values) {
        if(values==null)return null;
        return values.stream().map(c->c!=null&&c.path()!=null&&java.util.Set.of("options.registration.originalPrice","options.registration.salePrice").contains(c.path())&&c.value() instanceof String s
                ?new CoupangEditing.Change(c.path(),c.optionId(),normalize(s),c.rowKey(),c.operation()):c).toList();
    }
}
