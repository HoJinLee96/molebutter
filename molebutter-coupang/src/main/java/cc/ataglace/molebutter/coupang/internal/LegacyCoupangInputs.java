package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
/**
 * Read-only historical intent decoder. Never serializes over an original
 * request or its hash.
 */
public final class LegacyCoupangInputs {
    private LegacyCoupangInputs() {
    }
    public static List<CoupangEditing.Change> intent(ObjectMapper json, JsonNode observed, JsonNode changes) {
        var result = new LinkedHashMap<String, CoupangEditing.Change>();
        var old = observed.deepCopy().asObject();
        if (!old.has("configuration") && old.path("markets").has("COUPANG"))
            old.set("configuration", old.path("markets").path("COUPANG").deepCopy());
        var before = CoupangWorkingCopyDocuments.normalize(
                json.treeToValue(CoupangWorkingCopyDocuments.legacy(json, old), CoupangWorkingCopies.Document.class),
                json);
        for (var change : changes) {
            String path = change.path("path").asString();
            boolean sharedMedia = (path.equals("media.images") || path.equals("media.contents"))
                    && !change.hasNonNull("optionId");
            if (sharedMedia || Set.of("configuration.overrides.description", "configuration.overrides.imageIds",
                    "stockMode", "productQuantity", "configuration.categoryCode").contains(path)) {
                var parts = path.split("\\.");
                var row = old;
                for (int n = 0; n < parts.length - 1; n++) {
                    if (!row.path(parts[n]).isObject())
                        row.putObject(parts[n]);
                    row = row.path(parts[n]).asObject();
                }
                row.set(parts[parts.length - 1], change.path("value"));
                var after = CoupangWorkingCopyDocuments.normalize(json.treeToValue(
                        CoupangWorkingCopyDocuments.legacy(json, old), CoupangWorkingCopies.Document.class), json);
                for (var edit : diff(json, before, after))
                    result.put(edit.path() + "|" + edit.optionId(), edit);
                before = after;
            } else if (Set.of("common.model", "common.taxType", "common.adultOnly").contains(path)) {
                String key = path.equals("common.model") ? "modelNo" : path.substring(7);
                for (var option : before.options()) {
                    var edit = new CoupangEditing.Change("options.registration." + key, option.id(),
                            json.convertValue(change.path("value"), Object.class));
                    result.put(edit.path() + "|" + edit.optionId(), edit);
                }
            } else {
                var selected = json.createArrayNode().add(change);
                for (var edit : changes(json, selected))
                    result.put(edit.path() + "|" + edit.optionId(), edit);
            }
        }
        return List.copyOf(result.values());
    }
    private static List<CoupangEditing.Change> diff(ObjectMapper json, CoupangWorkingCopies.Document before,
            CoupangWorkingCopies.Document after) {
        var out = new ArrayList<CoupangEditing.Change>();
        var a = json.valueToTree(before);
        var b = json.valueToTree(after);
        for (String group : List.of("basic", "settings", "delivery"))
            for (var field : b.path(group).properties())
                if (!a.path(group).path(field.getKey()).equals(field.getValue()))
                    out.add(new CoupangEditing.Change(group + "." + field.getKey(), null,
                            json.convertValue(field.getValue(), Object.class)));
        for (var option : b.path("options")) {
            String id = option.path("id").asString();
            JsonNode prior = null;
            for (var v : a.path("options"))
                if (id.equals(v.path("id").asString()))
                    prior = v;
            if (prior == null)
                throw new InputValidationFailure("이전 수정의 옵션 UUID를 확인하세요.");
            for (var field : option.path("registration").properties())
                if (!prior.path("registration").path(field.getKey()).equals(field.getValue()))
                    out.add(new CoupangEditing.Change("options.registration." + field.getKey(), id,
                            json.convertValue(field.getValue(), Object.class)));
            for (String key : List.of("images", "contents"))
                if (!prior.path(key).equals(option.path(key)))
                    out.add(new CoupangEditing.Change("options." + key, id,
                            json.convertValue(option.path(key), Object.class)));
        }
        return out;
    }

    public static List<CoupangEditing.Change> changes(ObjectMapper json, JsonNode old) {
        var out = new ArrayList<CoupangEditing.Change>();
        for (var c : old) {
            String path = c.path("path").asString();
            String option = c.hasNonNull("optionId") ? c.path("optionId").asString() : null;
            var value = c.path("value");
            var aliases = Map.ofEntries(Map.entry("common.name", "basic.sellerProductName"),
                    Map.entry("common.productName", "basic.generalProductName"),
                    Map.entry("common.brand", "basic.brand"), Map.entry("common.manufacturer", "settings.manufacture"),
                    Map.entry("configuration.overrides.name", "basic.sellerProductName"),
                    Map.entry("configuration.overrides.productName", "basic.displayProductName"),
                    Map.entry("configuration.overrides.brand", "basic.brand"),
                    Map.entry("options.name", "options.itemName"),
                    Map.entry("options.price", "options.registration.salePrice"),
                    Map.entry("options.quantity", "options.registration.maximumBuyCount"),
                    Map.entry("options.sku", "options.registration.externalVendorSku"),
                    Map.entry("configuration.coupang.documents", "documents"));
            if (aliases.containsKey(path))
                path = aliases.get(path);
            else if (path.startsWith("configuration.coupang.options."))
                path = "options." + path.substring(30);
            else if (path.startsWith("configuration.coupang."))
                path = path.substring(22);
            if (Set.of("settings", "delivery", "options.registration").contains(path) && value.isArray()) {
                for (var field : value)
                    out.add(new CoupangEditing.Change(path + "." + field.path("name").asString(), option,
                            json.convertValue(field.path("value"), Object.class)));
                continue;
            }
            if (path.startsWith("delivery.")) {
                var alias = Map.ofEntries(Map.entry("method", "deliveryMethod"),
                        Map.entry("carrier", "deliveryCompanyCode"), Map.entry("chargeType", "deliveryChargeType"),
                        Map.entry("charge", "deliveryCharge"), Map.entry("freeOver", "freeShipOverAmount"),
                        Map.entry("initialReturnCharge", "deliveryChargeOnReturn"),
                        Map.entry("remoteArea", "remoteAreaDeliverable"), Map.entry("bundle", "unionDeliveryType"),
                        Map.entry("outboundCode", "outboundShippingPlaceCode"),
                        Map.entry("returnCode", "returnCenterCode"), Map.entry("returnName", "returnChargeName"),
                        Map.entry("returnContact", "companyContactNumber"), Map.entry("returnZip", "returnZipCode"),
                        Map.entry("jejuCharge", "jeju"), Map.entry("remoteCharge", "notJeju"));
                path = "delivery." + alias.getOrDefault(path.substring(9), path.substring(9));
            }
            if (path.equals("media.images") || path.equals("media.contents")) {
                if (option == null)
                    throw new InputValidationFailure("공용 이미지 수정 의도의 옵션 범위를 사전 확인하세요.");
                var rows = json.createArrayNode();
                for (var row : value) {
                    var copy = row.deepCopy().asObject();
                    copy.remove("optionId");
                    if (path.equals("media.images")) {
                        copy.put("type",
                                row.path("representative").asBoolean()
                                        ? "REPRESENTATION"
                                        : row.path("type").asString("DETAIL"));
                        copy.remove("representative");
                    } else {
                        copy.set("content", copy.remove("value"));
                        copy.put("detailType", copy.path("type").asString().equals("IMAGE") ? "IMAGE" : "TEXT");
                    }
                    rows.add(copy);
                }
                value = rows;
                path = path.equals("media.images") ? "options.images" : "options.contents";
            }
            var next = new CoupangEditing.Change(path, option, json.convertValue(value, Object.class));
            CoupangInputs.check(next);
            out.add(next);
        }
        return List.copyOf(out);
    }
}
