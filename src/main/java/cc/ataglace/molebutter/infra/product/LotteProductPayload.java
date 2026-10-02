package cc.ataglace.molebutter.infra.product;

import java.util.*;
import tools.jackson.databind.JsonNode;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import static cc.ataglace.molebutter.infra.product.NaverSearchPayload.text;

/** 롯데ON 본상품 응답만 해석한다. 선택 SKU 재고를 다른 옵션에 복제하지 않는다. */
final class LotteProductPayload {
    private LotteProductPayload() {
    }

    static boolean matches(JsonNode root, String id) {
        if (!"200".equals(text(root, "returnCode")))
            return false;
        var b = root.path("data").path("basicInfo");
        return id.equals(text(b, "sitmNo")) || id.equals(text(b, "pdNo"));
    }

    record Inventory(List<SourceOption> options, boolean complete) {
    }

    static Inventory inventory(JsonNode root, String id) {
        if (!matches(root, id))
            return new Inventory(List.of(), false);
        var d = root.path("data");
        var b = d.path("basicInfo");
        var info = d.path("optionInfo");
        String sku = text(b, "sitmNo"), pd = text(b, "pdNo");
        var axes = info.path("optionList");
        Map<String, SourceOption> options = new LinkedHashMap<>();
        boolean complete = false;
        if (axes.isArray() && axes.size() == 1 && axes.path(0).path("options").isArray()) {
            var choices = axes.path(0).path("options");
            var mappings = info.path("optionMappingInfo");
            complete = !choices.isEmpty();
            Set<String> seen = new HashSet<>(), ambiguous = new HashSet<>();
            for (var choice : choices) {
                String key = text(choice, "value"), label = text(choice, "label");
                var m = mappings.path(key);
                String mapped = text(m, "sitmNo");
                if (key.isBlank() || label.isBlank() || mapped.isBlank() || pd.isBlank() || !pd.equals(text(m, "spdNo"))
                        || !mapped.startsWith(pd + "_")) {
                    complete = false;
                    continue;
                }
                if (!seen.add(mapped)) {
                    complete = false;
                    ambiguous.add(mapped);
                    options.remove(mapped);
                    continue;
                }
                var option = stock(mapped, label, m, text(m, "sitmNoSlStatCd"));
                String productState = text(m, "spdNoSlStatCd");
                if (!productState.isBlank() && !"SALE".equals(productState))
                    option = new SourceOption(mapped, label, option.stock(), "UNAVAILABLE");
                if (choice.path("disabled").asBoolean(false) && !"SOLD_OUT".equals(option.state()))
                    option = new SourceOption(mapped, label, option.stock(), "UNAVAILABLE");
                if (!ambiguous.contains(mapped))
                    options.put(mapped, option);
            }
        }
        String label = text(b, "sitmNm");
        if (!sku.isBlank() && !label.isBlank()) {
            var selected = stock(sku, label, d.path("stckInfo"), text(b, "sitmSlStatCd"));
            var mapped = options.get(sku);
            if (mapped == null) {
                options.put(sku, selected);
                complete = false;
            } else {
                // 숨긴 수량이나 상충하는 수량은 확정하지 않고 옵션 원문 이름을 사용한다.
                boolean hidden = d.path("stckInfo").path("hideStkQty").asBoolean(false)
                        || "N".equals(text(d.path("stckInfo"), "stkMgtYn"));
                if (hidden || mapped.stock() != null && selected.stock() != null
                        && !mapped.stock().equals(selected.stock())) {
                    options.put(sku, new SourceOption(sku, mapped.label(), null, "STOCK_UNKNOWN"));
                    complete = false;
                } else if ("UNAVAILABLE".equals(selected.state()))
                    options.put(sku, new SourceOption(sku, mapped.label(), mapped.stock(), "UNAVAILABLE"));
            }
            var ids = b.path("sitmNoList");
            if (axes.isArray() && axes.isEmpty() && ids.isArray() && ids.size() == 1
                    && sku.equals(ids.path(0).asText()))
                complete = true;
        }
        var declared = b.path("sitmNoList");
        if (declared.isArray())
            for (var declaredId : declared)
                if (!options.containsKey(declaredId.asText()))
                    complete = false;
        return new Inventory(List.copyOf(options.values()), complete);
    }

    private static SourceOption stock(String id, String label, JsonNode n, String status) {
        var value = n.path("stkQty");
        Long quantity = null;
        if (!n.path("hideStkQty").asBoolean(false) && !"N".equals(text(n, "stkMgtYn")) && value.isIntegralNumber()
                && value.canConvertToLong() && value.asLong() >= 0)
            quantity = value.asLong();
        String state = status.isBlank() ? "STOCK_UNKNOWN"
                : !"SALE".equals(status) ? "UNAVAILABLE"
                        : quantity == null ? "STOCK_UNKNOWN" : quantity == 0 ? "SOLD_OUT" : "AVAILABLE";
        return new SourceOption(id, label, quantity, state);
    }
}
