package cc.ataglace.molebutter.coupang.internal;

import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

/**
 * Reads immutable v1 Coupang evidence without rewriting the persisted document
 * or its hash. Current working copies and sessions use native JSON directly;
 * this adapter remains for applied migrations and immutable submission
 * evidence.
 */
public final class CoupangSnapshots {
    private CoupangSnapshots() {
    }
    public static <T> T read(ObjectMapper json, String value, Class<T> type) {
        var tree = json.readTree(value);
        normalize(json, tree);
        return json.treeToValue(tree, type);
    }
    public static <T> T read(ObjectMapper json, String value, JavaType type) {
        var tree = json.readTree(value);
        normalize(json, tree);
        return json.treeToValue(tree, type);
    }
    public static <T> T read(ObjectMapper json, String value, tools.jackson.core.type.TypeReference<T> type) {
        return read(json, value, json.getTypeFactory().constructType(type));
    }
    private static void normalize(ObjectMapper json, JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has("common") && object.has("options")) {
                var nativeDoc = cc.ataglace.molebutter.coupang.api.CoupangWorkingCopyDocuments.legacy(json, object);
                object.removeAll();
                for (var field : nativeDoc.properties())
                    object.set(field.getKey(), field.getValue());
            }
            if (object.has("draftId"))
                object.set("workingCopyId", object.remove("draftId"));
            if (object.has("steps") && object.path("schemaVersion").asInt(1) < 3)
                object.remove("editIntent");
            if (object.has("observed") && object.path("observed").has("common") && object.has("changes"))
                object.set("changes", json.valueToTree(LegacyCoupangInputs.changes(json, object.path("changes"))));
            for (String field : new String[]{"market", "originMarket", "importMarket"}) {
                var value = object.get(field);
                if (value != null && !value.isNull() && (!value.isString() || !"COUPANG".equals(value.asString())))
                    throw new InputValidationFailure("쿠팡 저장 문서의 대상 값이 일치하지 않습니다.");
                object.remove(field);
            }
            var selected = object.remove("selectedMarkets");
            if (selected != null) {
                if (!selected.isArray())
                    throw new InputValidationFailure("보관된 쿠팡 대상을 확인해 주세요.");
                for (var item : selected)
                    if (!"COUPANG".equals(item.asString()))
                        throw new InputValidationFailure("쿠팡 외의 저장 대상이 있습니다.");
            }
            var configurations = object.remove("markets");
            if (configurations != null) {
                if (!configurations.isObject() || configurations.size() != 1 || !configurations.has("COUPANG")
                        || object.has("configuration"))
                    throw new InputValidationFailure("보관된 쿠팡 설정을 확인해 주세요.");
                object.set("configuration", configurations.get("COUPANG"));
            }
            if (object.has("observedHash") && object.path("action").asString().equals("MARKET"))
                object.put("action", "COUPANG");
            var channel = object.get("channelProductId");
            if (channel != null && !channel.isNull())
                throw new InputValidationFailure("쿠팡 저장 문서에 다른 채널 연결이 있습니다.");
            object.remove("channelProductId");
            var old = object.get("targets");
            if (old != null) {
                if (!old.isArray() || old.size() > 1)
                    throw new InputValidationFailure("쿠팡 저장 문서의 대상 수가 일치하지 않습니다.");
                if (object.has("target"))
                    throw new InputValidationFailure("쿠팡 대상 형식이 중복되었습니다.");
                object.set("target", old.isEmpty() ? NullNode.instance : old.get(0));
                object.remove("targets");
            }
            for (var entry : object.properties())
                normalize(json, entry.getValue());
        } else if (node.isArray())
            for (var child : node)
                normalize(json, child);
    }
}
