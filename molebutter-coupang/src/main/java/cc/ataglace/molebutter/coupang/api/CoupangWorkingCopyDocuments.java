package cc.ataglace.molebutter.coupang.api;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;

/**
 * Flat Coupang inputs and the read-only V46 conversion used by
 * migration/evidence readers.
 */
public final class CoupangWorkingCopyDocuments {
    private CoupangWorkingCopyDocuments() {
    }
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final Set<String> CONTENT_TYPES = Set.of("HTML", "IMAGE", "IMAGE_NO_SPACE", "TEXT",
            "IMAGE_TEXT", "TEXT_IMAGE", "IMAGE_IMAGE", "TEXT_TEXT", "TITLE");
    public static final Set<String> BASIC = Set.of("sellerProductName", "displayProductName", "generalProductName",
            "brand", "productGroup", "displayCategoryCode");
    public static final Set<String> SETTINGS = Set.of("saleStartedAt", "saleEndedAt", "manufacture", "brandId",
            "productGroup", "extraInfoMessage", "bundleInfo.bundleType");
    public static final Set<String> DELIVERY = Set.of("deliveryMethod", "deliveryCompanyCode", "deliveryChargeType",
            "deliveryCharge", "freeShipOverAmount", "deliveryChargeOnReturn", "returnCharge", "remoteAreaDeliverable",
            "unionDeliveryType", "outboundShippingPlaceCode", "returnCenterCode", "returnChargeName",
            "companyContactNumber", "returnZipCode", "returnAddress", "returnAddressDetail", "jeju", "notJeju");
    public static final Set<String> REGISTRATION = Set.of("externalVendorSku", "originalPrice", "salePrice",
            "maximumBuyCount", "maximumBuyForPerson", "maximumBuyForPersonPeriod", "outboundShippingTimeDay",
            "unitCount", "adultOnly", "taxType", "parallelImported", "overseasPurchased", "pccNeeded",
            "bestPriceGuaranteed3P", "barcode", "emptyBarcode", "emptyBarcodeReason", "modelNo", "searchTags",
            "offerCondition", "offerDescription", "autoPricingInfo.minSalePrice", "autoPricingInfo.active");
    public static Document normalize(Document d, ObjectMapper json) {
        if (d == null || d.options() == null || d.options().isEmpty() || d.options().size() > 200)
            throw invalid("옵션은 1~200개로 입력해 주세요.");
        if (json.writeValueAsBytes(d).length > MAX_BYTES)
            throw invalid("작업본은 5MiB 이하로 입력해 주세요.");
        var ids = new HashSet<String>();
        var options = new ArrayList<Option>();
        for (var o : d.options()) {
            if (o == null || !uuid(o.id()) || !ids.add(o.id()))
                throw invalid("옵션 UUID를 확인해 주세요.");
            var imageIds = new HashSet<String>();
            for (var image : list(o.images()))
                if (image == null || !uuid(image.id()) || !imageIds.add(image.id())
                        || image.assetId() != null && !uuid(image.assetId())
                        || image.assetId() == null && !https(image.url()))
                    throw invalid("이미지 식별자와 HTTPS 주소를 확인해 주세요.");
            var contentIds = new HashSet<String>();
            long logos=list(o.contents()).stream().filter(c->c!=null&&"BRAND_LOGO".equals(c.purpose())).count();if(logos>1)throw invalid("옵션당 브랜드 로고는 하나만 지정해 주세요.");
            for (var c : list(o.contents()))
                if (c == null || !uuid(c.id()) || !contentIds.add(c.id()) || c.assetId() != null && !uuid(c.assetId())
                        || !CONTENT_TYPES.contains(Objects.toString(c.type(), "")) || c.purpose()!=null&&(!"BRAND_LOGO".equals(c.purpose())||!"IMAGE".equals(c.type())||!"IMAGE".equals(c.detailType())))
                    throw invalid("상세설명 입력을 확인해 주세요.");
            var registration=map(o.registration());
            for(String key:List.of("originalPrice","salePrice"))if(registration.containsKey(key))registration.put(key,CoupangPrices.normalize(registration.get(key)));
            options.add(new Option(o.id(), text(o.itemName()), registration, list(o.attributes()),
                    list(o.images()), list(o.contents()), list(o.notices()), list(o.certifications()),
                    list(o.notices()).isEmpty() ? null : o.notices().getFirst().category()));
        }
        var basic = map(d.basic());
        for (String key : BASIC)
            basic.putIfAbsent(key, "");
        if (!BASIC.containsAll(basic.keySet()))
            throw invalid("쿠팡 기본 정보의 필드를 확인해 주세요.");
        return new Document(basic, List.copyOf(options), map(d.delivery()), map(d.settings()), list(d.documents()));
    }
    public static Set<String> assetIds(Document d) {
        var ids = new HashSet<String>();
        if (d == null)
            return ids;
        for (var o : list(d.options())) {
            for (var i : list(o.images()))
                if (i.assetId() != null)
                    ids.add(i.assetId());
            for (var c : list(o.contents())) {
                if (c.assetId() != null)
                    ids.add(c.assetId());
                var m = cc.ataglace.molebutter.media.api.EditedImages.ASSET.matcher(text(c.content()));
                while (m.find())
                    ids.add(m.group(1));
            }
        }
        return ids;
    }
    public static String name(Document d) {
        return d.basic().getOrDefault("sellerProductName", "");
    }
    public static boolean https(String value) {
        if (value == null)
            return false;
        try {
            var u = java.net.URI.create(value);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null;
        } catch (RuntimeException bad) {
            return false;
        }
    }
    public static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (RuntimeException bad) {
            return false;
        }
    }
    public static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
    public static Map<String, String> map(Map<String, String> value) {
        var out = new LinkedHashMap<String, String>();
        if (value != null)
            value.forEach((k, v) -> {
                if (k == null || v == null || k.length() > 200 || v.length() > 200000)
                    throw invalid("입력 필드와 값을 확인해 주세요.");
                out.put(k, v);
            });
        return out;
    }
    public static String text(String value) {
        return Objects.toString(value, "");
    }
    private static InputValidationFailure invalid(String message) {
        return new InputValidationFailure(message);
    }

    /**
     * Original JSON is archived separately; this returns only its effective Coupang
     * input.
     */
    // Historical V47 calls this exact public signature; keep it without moving the
    // applied migration.
    public static ObjectNode legacy(ObjectMapper json, JsonNode old) {
        if (old.has("basic") && old.has("options"))
            return old.deepCopy().asObject();
        if (!old.has("common"))
            throw invalid("이전 작업본의 형식을 확인해 주세요.");
        var common = old.path("common");
        var config = old.has("configuration") ? old.path("configuration") : old.path("markets").path("COUPANG");
        var cp = config.path("coupang");
        var over = config.path("overrides");
        var out = json.createObjectNode();
        var basic = out.putObject("basic");
        basic.put("sellerProductName", effective(over, "name", common.path("name").asString("")));
        basic.put("displayProductName", effective(over, "productName", common.path("name").asString("")));
        basic.put("generalProductName", common.path("productName").asString(""));
        basic.put("brand", effective(over, "brand", common.path("brand").asString("")));
        basic.put("displayCategoryCode", config.path("categoryCode").asString(""));
        var settings = fields(json, cp.path("settings"));
        settings.remove("vendorUserId");
        settings.remove("requested");
        for (String key : List.of("saleStartedAt", "saleEndedAt"))
            if (!settings.has(key))
                settings.put(key, common.path(key).asString(""));
        if (!settings.has("manufacture"))
            settings.put("manufacture", common.path("manufacturer").asString(""));
        out.set("settings", settings);
        out.set("documents", cp.path("documents").isArray() ? cp.path("documents").deepCopy() : json.createArrayNode());
        var delivery = fields(json, cp.path("delivery"));
        var aliases = Map.ofEntries(Map.entry("method", "deliveryMethod"), Map.entry("carrier", "deliveryCompanyCode"),
                Map.entry("chargeType", "deliveryChargeType"), Map.entry("charge", "deliveryCharge"),
                Map.entry("freeOver", "freeShipOverAmount"), Map.entry("initialReturnCharge", "deliveryChargeOnReturn"),
                Map.entry("returnCharge", "returnCharge"), Map.entry("remoteArea", "remoteAreaDeliverable"),
                Map.entry("bundle", "unionDeliveryType"), Map.entry("outboundCode", "outboundShippingPlaceCode"),
                Map.entry("returnCode", "returnCenterCode"), Map.entry("returnName", "returnChargeName"),
                Map.entry("returnContact", "companyContactNumber"), Map.entry("returnZip", "returnZipCode"),
                Map.entry("returnAddress", "returnAddress"), Map.entry("returnAddressDetail", "returnAddressDetail"));
        aliases.forEach((a, b) -> {
            if (!delivery.has(b))
                delivery.put(b, old.path("delivery").path(a).asString(""));
        });
        out.set("delivery", delivery);
        var options = out.putArray("options");
        for (var option : old.path("options")) {
            String id = option.path("id").asString();
            JsonNode setting = json.createObjectNode(), override = json.createObjectNode();
            for (var v : cp.path("options"))
                if (id.equals(v.path("optionId").asString()))
                    setting = v;
            for (var v : over.path("options"))
                if (id.equals(v.path("optionId").asString()))
                    override = v;
            var next = options.addObject();
            next.put("id", id);
            next.put("itemName", option.path("name").asString(""));
            var registration = fields(json, setting.path("registration"));
            var readonly = new ArrayList<String>();
            for (var field : registration.properties())
                if (field.getKey().startsWith("autoPricingInfoView."))
                    readonly.add(field.getKey());
            readonly.forEach(registration::remove);
            registration.put("externalVendorSku", effective(override, "sku", option.path("sku").asString("")));
            registration.put("salePrice", effective(override, "price", option.path("price").asString("")));
            registration.put("maximumBuyCount",
                    old.path("stockMode").asString().equals("PRODUCT")
                            ? old.path("productQuantity").asString("")
                            : effective(override, "quantity", option.path("quantity").asString("")));
            if (!registration.has("originalPrice"))
                registration.put("originalPrice", option.path("originalPrice").asString(""));
            for (String key : List.of("adultOnly", "taxType", "modelNo"))
                if (!registration.has(key))
                    registration.put(key, common.path(key.equals("modelNo") ? "model" : key).asString(""));
            next.set("registration", registration);
            var attributes = setting.path("attributes").isArray()
                    ? setting.path("attributes").deepCopy()
                    : option.path("attributes").deepCopy();
            if (attributes.isArray())
                for (var a : attributes)
                    if (!a.has("exposed"))
                        a.asObject().put("exposed", "EXPOSED");
            next.set("attributes", attributes.isArray() ? attributes : json.createArrayNode());
            for (String key : List.of("notices", "certifications"))
                next.set(key, setting.path(key).isArray() ? setting.path(key).deepCopy() : json.createArrayNode());
            var images = next.putArray("images");
            boolean ownImages = old.path("media").path("imageOverrides").path(id).asBoolean();
            for (var image : old.path("media").path("images")) {
                String owner = image.path("optionId").asString("");
                if (!(ownImages ? id.equals(owner) : owner.isEmpty() || id.equals(owner)))
                    continue;
                if (over.path("imageIds").isArray() && !contains(over.path("imageIds"), image.path("id").asString()))
                    continue;
                var i = images.addObject();
                i.set("id", image.path("id"));
                i.set("assetId", image.path("assetId"));
                i.set("url", image.path("url"));
                i.put("type",
                        image.path("representative").asBoolean()
                                ? "REPRESENTATION"
                                : image.path("type").asString("DETAIL"));
                i.put("order", image.path("order").asInt());
            }
            var contents = next.putArray("contents");
            if (over.hasNonNull("description")) {
                var c = contents.addObject();
                c.put("id",
                        UUID.nameUUIDFromBytes((id + ":description").getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .toString());
                c.put("type", "HTML");
                c.put("detailType", "TEXT");
                c.set("content", over.path("description"));
            } else {
                boolean own = old.path("media").path("contentOverrides").path(id).asBoolean();
                for (var content : old.path("media").path("contents")) {
                    String owner = content.path("optionId").asString("");
                    if (!(own ? id.equals(owner) : owner.isEmpty() || id.equals(owner)))
                        continue;
                    var c = contents.addObject();
                    c.set("id", content.path("id"));
                    c.set("type", content.path("type"));
                    c.put("detailType", content.path("type").asString().equals("IMAGE") ? "IMAGE" : "TEXT");
                    c.set("content", content.path("value"));
                    c.set("assetId", content.path("assetId"));
                }
            }
        }
        return out;
    }
    private static boolean contains(JsonNode list, String id) {
        for (var v : list)
            if (id.equals(v.asString()))
                return true;
        return false;
    }
    private static String effective(JsonNode o, String key, String inherited) {
        return o.hasNonNull(key) ? o.path(key).asString() : inherited;
    }
    private static ObjectNode fields(ObjectMapper json, JsonNode fields) {
        var out = json.createObjectNode();
        for (var f : fields)
            if (f.hasNonNull("name") && f.hasNonNull("value"))
                out.set(f.path("name").asString(), f.path("value"));
        return out;
    }
}
