package cc.ataglace.molebutter.common.api;


import java.util.List;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

public final class BusinessIds {
    private BusinessIds() {}
    public static long next() { return com.github.f4b6a3.tsid.TsidCreator.getTsid().toLong(); }
    public static List<Long> parseList(List<String> values) {
        if(values==null)return List.of();
        if(values.size()>5000)throw new InputValidationFailure("한 번에 5,000개까지 선택해 주세요.");
        try {
            return values.stream().map(Long::valueOf).peek(id->{if(id<=0)throw new InputValidationFailure("ID가 올바르지 않습니다.");}).distinct().sorted().toList();
        } catch(NumberFormatException e) { throw new InputValidationFailure("ID가 올바르지 않습니다."); }
    }
}
