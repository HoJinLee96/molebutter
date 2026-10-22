package cc.ataglace.molebutter.coupang.api;

import java.util.List;

/** Local suggestions only; never saves a working copy or calls Coupang. */
public interface CoupangSearchTags {
    record Context(String gender, String material, String category, List<String> colors) {}
    record Input(String title, String brand, Context context) {}
    record Excluded(String value, String reason) {}
    record Result(List<String> tags, List<Excluded> excluded) {}
    Result generate(Input input);
}
