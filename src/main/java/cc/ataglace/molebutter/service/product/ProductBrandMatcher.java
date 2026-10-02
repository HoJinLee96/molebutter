package cc.ataglace.molebutter.service.product;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 등록된 이름 하나가 독립된 단어로 확인될 때만 브랜드를 선택한다. */
public final class ProductBrandMatcher {
    private final Map<Long, Pattern> patterns = new LinkedHashMap<>();

    public ProductBrandMatcher(Map<Long, String> brands) {
        brands.forEach((id, name) -> {
            String normalized = normalize(name);
            if (!normalized.isBlank()) patterns.put(id, Pattern.compile(
                "(?<![\\p{L}\\p{N}\\p{M}])" + Pattern.quote(normalized) + "(?![\\p{L}\\p{N}\\p{M}])"));
        });
    }

    public Long match(String title) {
        String normalized = normalize(title);
        Long found = null;
        for (var entry : patterns.entrySet()) {
            if (!entry.getValue().matcher(normalized).find()) continue;
            if (found != null) return null; // 복수 브랜드나 정규화 후 같은 이름도 임의로 선택하지 않는다.
            found = entry.getKey();
        }
        return found;
    }

    public Long matchAll(List<String> titles) {
        if (titles.isEmpty()) return null;
        Long found = null;
        for (String title : titles) {
            Long current = match(title);
            if (current == null || found != null && !found.equals(current)) return null;
            found = current;
        }
        return found;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").trim();
    }
}
