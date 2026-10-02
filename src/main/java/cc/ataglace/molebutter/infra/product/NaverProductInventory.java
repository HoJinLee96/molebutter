package cc.ataglace.molebutter.infra.product;

import java.util.*;
import tools.jackson.databind.JsonNode;
import cc.ataglace.molebutter.dto.product.ProductDtos.SourceOption;
import static cc.ataglace.molebutter.infra.product.NaverSearchPayload.*;

/** 본상품의 명시된 조합만 읽는다. 상품 합계 재고를 옵션에 배분하지 않는다. */
final class NaverProductInventory {
    record Result(List<SourceOption> options, boolean complete) {
    }

    static Result read(JsonNode root, String id) {
        if (!SupplierMetadataParser.naverMatches(root, id))
            return new Result(List.of(), false);
        var body = root.path("contents").isObject() ? root.path("contents") : root;
        var info = body.path("optionInfo");
        var rows = body.path("optionCombinations");
        var nested = info.path("optionCombinations");
        if (rows.isArray() && nested.isArray() && !rows.equals(nested))
            return new Result(List.of(), false);
        if (!rows.isArray())
            rows = nested;
        var usable = body.has("optionUsable") ? body.path("optionUsable") : info.path("optionUsable");
        if (body.has("optionUsable") && info.has("optionUsable")
                && !body.path("optionUsable").equals(info.path("optionUsable")))
            return new Result(List.of(), false);
        if (!usable.isMissingNode() && !usable.isNull() && !usable.isBoolean())
            return new Result(List.of(), false);
        boolean external = body.path("useExternalStock").asBoolean(false)
                || root.path("useExternalStock").asBoolean(false);
        if (usable.isBoolean() && !usable.asBoolean()) {
            if (rows.isArray() && !rows.isEmpty())
                return new Result(List.of(), false);
            for (var node : List.of(body, info))
                for (String field : List.of("options", "optionStandards", "optionCombinations")) {
                    var value = node.path(field);
                    if (!value.isMissingNode() && !value.isNull() && (!value.isArray() || !value.isEmpty()))
                        return new Result(List.of(), false);
                }
            return new Result(List.of(option(id, "단일상품", body, body, external)), true);
        }
        if (usable.isBoolean() && usable.asBoolean() && simpleOnly(body, info)) {
            var total = option(id, "상품 전체", body, body, external);
            return new Result(
                    List.of(new SourceOption(total.id(), total.label(), total.stock(), total.state(), "PRODUCT")),
                    true);
        }
        if (!rows.isArray() || rows.isEmpty())
            return new Result(List.of(), false);
        var result = new LinkedHashMap<String, SourceOption>();
        var ambiguous = new HashSet<String>();
        boolean complete = true;
        for (var row : rows) {
            String key = text(row, "id");
            String label = String.join(" / ",
                    List.of(text(row, "optionName1"), text(row, "optionName2"), text(row, "optionName3")).stream()
                            .filter(s -> !s.isBlank()).toList());
            if (key.isBlank() || label.isBlank()) {
                complete = false;
                continue;
            }
            var option = option(key, label, row, body, external);
            var prior = result.putIfAbsent(key, option);
            if (prior != null && !prior.equals(option)) {
                ambiguous.add(key);
                complete = false;
            }
        }
        ambiguous.forEach(result::remove);
        return new Result(List.copyOf(result.values()), complete && !result.isEmpty());
    }

    private static boolean simpleOnly(JsonNode body, JsonNode info) {
        for (var node : List.of(body, info))
            for (String field : List.of("optionCombinations", "optionStandards")) {
                var value = node.path(field);
                if (!value.isMissingNode() && !value.isNull() && (!value.isArray() || !value.isEmpty()))
                    return false;
            }
        var outer = body.path("options");
        var inner = info.path("options");
        if (!outer.isMissingNode() && !inner.isMissingNode() && !outer.equals(inner))
            return false;
        var choices = outer.isMissingNode() ? inner : outer;
        if (!choices.isArray() || choices.isEmpty())
            return false;
        var ids = new HashSet<String>();
        for (var choice : choices) {
            String id = text(choice, "id");
            if (!"SIMPLE".equals(text(choice, "optionType")) || id.isBlank() || !ids.add(id)
                    || text(choice, "groupName").isBlank() || text(choice, "name").isBlank())
                return false;
        }
        return true;
    }

    private static SourceOption option(String id, String label, JsonNode row, JsonNode product, boolean external) {
        Long stock = external ? null : number(row, "stockQuantity");
        String status = text(product, "productStatusType", "statusType");
        String channel = text(product, "channelProductStatusType");
        boolean disabled = row.path("usable").isBoolean() && !row.path("usable").asBoolean()
                || !status.isBlank() && !"SALE".equals(status) || !channel.isBlank() && !"NORMAL".equals(channel);
        String state = Objects.equals(stock, 0L) ? "SOLD_OUT"
                : disabled ? "UNAVAILABLE" : stock != null && "SALE".equals(status) ? "AVAILABLE" : "STOCK_UNKNOWN";
        return new SourceOption(id, label, stock, state);
    }
}
