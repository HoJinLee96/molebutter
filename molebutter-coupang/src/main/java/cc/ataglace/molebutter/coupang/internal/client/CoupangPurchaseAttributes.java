package cc.ataglace.molebutter.coupang.internal.client;

import java.time.LocalDate;
import java.util.*;
import cc.ataglace.molebutter.coupang.api.CoupangCatalog.Attribute;
import cc.ataglace.molebutter.coupang.api.CoupangEditor.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Permission always comes from the fresh server response, never from editable
 * draft fields.
 */
final class CoupangPurchaseAttributes {
    private CoupangPurchaseAttributes() {
    }
    static boolean purchase(String exposed, String name) {
        return "EXPOSED".equals(exposed)
                && !Set.of("Manufacturer Part Number", "Global Trade Item Number").contains(name);
    }
    static List<Attribute> read(JsonNode rows) {
        var result = new ArrayList<Attribute>();
        for (var row : rows)
            result.add(new Attribute(row.path("attributeTypeName").asString(),
                    row.path("attributeValueName").asString(), row.path("exposed").asString()));
        return List.copyOf(result);
    }
    static ArrayNode patch(ObjectMapper json, JsonNode latest, List<Attribute> before, List<Attribute> after,
            CategoryRules rules) {
        var old = new LinkedHashMap<List<String>, Attribute>();
        for (var a : before)
            if (old.put(key(a), a) != null)
                fail("중복 속성 이름과 구분을 확인해 주세요.");
        var proposed = new LinkedHashMap<List<String>, Attribute>();
        for (var a : after)
            if (proposed.put(key(a), a) != null)
                fail("중복 속성 이름을 확인해 주세요.");
        var changed = new HashSet<List<String>>();
        for (var a : before)
            if (purchase(a.exposed(), a.name()) && !proposed.containsKey(key(a)))
                fail("기존 구매옵션의 이름 변경·삭제는 지원하지 않습니다.");
        for (var a : after) {
            var prior = old.get(key(a));
            if (Objects.equals(prior, a) || prior == null && (a.value() == null || a.value().isBlank()))
                continue;
            if (prior != null && (purchase(a.exposed(), a.name())
                    || Set.of("Manufacturer Part Number", "Global Trade Item Number").contains(a.name()))) {
                JsonNode remote = null;
                for (var row : latest)
                    if (key(a).equals(key(row)))
                        remote = row;
                if (remote == null || !Boolean.TRUE.equals(CoupangProductClient.editable(remote.path("editable"))))
                    fail(a.name() + "의 수정 권한이 없거나 확인되지 않았습니다. 최신 상품을 다시 조회해 주세요.");
            }
            if (!purchase(a.exposed(), a.name()))
                continue;
            changed.add(key(a));
            if (rules == null)
                fail("구매 속성의 최신 카테고리 규격을 확인할 수 없습니다.");
            var rule = rules.attributes().stream()
                    .filter(r -> r.name().equals(a.name()) && "EXPOSED".equals(r.exposed())).findFirst().orElse(null);
            if (prior == null && (rule == null || !"MANDATORY".equals(rule.required())))
                fail("기존 상품에는 누락된 필수 구매옵션만 추가할 수 있습니다.");
            if (prior == null && rule != null && rule.groupNumber() != null && !"NONE".equals(rule.groupNumber())
                    && rules.attributes().stream().anyMatch(r -> Objects.equals(rule.groupNumber(), r.groupNumber())
                            && old.containsKey(List.of(r.name(), r.exposed()))))
                fail("기존 그룹 구매옵션 항목은 교체할 수 없습니다.");
            if (rule == null)
                fail(a.name() + "의 최신 카테고리 규격을 확인할 수 없습니다.");
            value(rule, a.value());
        }
        for (var identity : changed) {
            var rule = rules.attributes().stream().filter(r -> List.of(r.name(), r.exposed()).equals(identity))
                    .findFirst().orElseThrow();
            if (rule.groupNumber() != null && !rule.groupNumber().isBlank() && !"NONE".equals(rule.groupNumber())) {
                var group = rules.attributes().stream().filter(
                        r -> "EXPOSED".equals(r.exposed()) && Objects.equals(rule.groupNumber(), r.groupNumber()))
                        .toList();
                long count = group.stream().filter(r -> {
                    var a = proposed.get(List.of(r.name(), r.exposed()));
                    return a != null && a.value() != null && !a.value().isBlank();
                }).count();
                if (count != 1)
                    fail("그룹 구매옵션은 한 항목만 입력해 주세요.");
            }
        }
        var result = json.createArrayNode();
        var seen = new HashSet<List<String>>();
        for (var row : latest) {
            var identity = key(row);
            seen.add(identity);
            var a = proposed.get(identity);
            var prior = old.get(identity);
            if (prior == null || Objects.equals(prior, a)) {
                result.add(row.deepCopy());
                continue;
            }
            if (a != null) {
                var updated = (ObjectNode) row.deepCopy();
                updated.put("attributeValueName", a.value());
                result.add(updated);
            }
        }
        for (var a : after)
            if (!seen.contains(key(a)) && a.value() != null && !a.value().isBlank()) {
                var row = json.createObjectNode();
                row.put("attributeTypeName", a.name());
                row.put("attributeValueName", a.value());
                row.put("exposed", a.exposed());
                result.add(row);
            }
        return result;
    }
    private static List<String> key(Attribute a) {
        return List.of(Objects.toString(a.name(), ""), Objects.toString(a.exposed(), ""));
    }
    private static List<String> key(JsonNode row) {
        return List.of(row.path("attributeTypeName").asString(), row.path("exposed").asString());
    }
    static String itemName(JsonNode attributes) {
        var entries = new ArrayList<JsonNode>();
        for (var a : attributes)
            if (purchase(a.path("exposed").asString(), a.path("attributeTypeName").asString())
                    && !a.path("attributeValueName").asString().isBlank())
                entries.add(a);
        entries.sort(Comparator.comparingInt(a -> "색상".equals(a.path("attributeTypeName").asString())
                ? 0
                : "사이즈".equals(a.path("attributeTypeName").asString()) ? 1 : 2));
        return entries.isEmpty()
                ? "단일 상품"
                : String.join(" ", entries.stream().map(a -> a.path("attributeValueName").asString().trim()).toList());
    }
    static void create(JsonNode items, CategoryRules rules) {
        if (rules == null)
            fail("최신 카테고리 규격을 확인할 수 없습니다.");
        var combinations = new HashSet<Map<String, String>>();
        for (var item : items) {
            var attrs = new LinkedHashMap<String, String>();
            for (var a : item.path("attributes"))
                if (purchase(a.path("exposed").asString(), a.path("attributeTypeName").asString())
                        && !a.path("attributeValueName").asString().isBlank()) {
                    String name = a.path("attributeTypeName").asString(),
                            value = a.path("attributeValueName").asString();
                    if (attrs.put(name, value) != null)
                        fail("중복 구매옵션 이름을 확인해 주세요.");
                    var rule = rules.attributes().stream()
                            .filter(r -> r.name().equals(name) && "EXPOSED".equals(r.exposed())).findFirst()
                            .orElseThrow(() -> new InputValidationFailure("카테고리에 없는 구매옵션입니다: " + name));
                    value(rule, value);
                }
            var groups = new HashSet<String>();
            for (var r : rules.attributes())
                if ("EXPOSED".equals(r.exposed()) && "MANDATORY".equals(r.required())
                        && purchase(r.exposed(), r.name())) {
                    if (r.groupNumber() != null && !r.groupNumber().isBlank() && !"NONE".equals(r.groupNumber()))
                        groups.add(r.groupNumber());
                    else if (!attrs.containsKey(r.name()))
                        fail(r.name() + "을 입력해 주세요.");
                }
            for (var r : rules.attributes())
                if (purchase(r.exposed(), r.name()) && r.groupNumber() != null && !r.groupNumber().isBlank()
                        && !"NONE".equals(r.groupNumber()) && attrs.containsKey(r.name()))
                    groups.add(r.groupNumber());
            for (var group : groups)
                if (rules.attributes().stream().filter(r -> purchase(r.exposed(), r.name())
                        && Objects.equals(group, r.groupNumber()) && attrs.containsKey(r.name())).count() != 1)
                    fail("그룹 구매옵션은 한 항목만 입력해 주세요.");
            if (!attrs.isEmpty() && !combinations.add(Map.copyOf(attrs)))
                fail("구매 옵션 조합이 중복되었습니다.");
        }
    }
    static void value(AttributeRule rule, String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 30)
            fail(rule.name() + "은 1~30자로 입력해 주세요.");
        if ("DATE".equals(rule.dataType()))
            try {
                LocalDate.parse(value);
            } catch (RuntimeException e) {
                fail(rule.name() + "의 날짜 형식을 확인해 주세요.");
            }
        if ("NUMBER".equals(rule.dataType())) {
            var matcher = java.util.regex.Pattern.compile("^-?\\d+(?:\\.\\d+)?\\s*(.*?)$").matcher(value);
            if (!matcher.matches())
                fail(rule.name() + "의 숫자 형식을 확인해 주세요.");
            String unit = matcher.group(1);
            var units = rule.usableUnits() == null ? List.<String>of() : rule.usableUnits();
            if (!units.isEmpty() && !units.contains(unit) || units.isEmpty() && !unit.isEmpty())
                fail(rule.name() + "의 허용 단위를 확인해 주세요.");
        }
    }
    private static void fail(String message) {
        throw new InputValidationFailure(message);
    }
}
