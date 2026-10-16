package cc.ataglace.molebutter.coupang.api;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway.*;
import static cc.ataglace.molebutter.coupang.api.CoupangWorkingCopyDocuments.*;
/**
 * Field-level editing of native Coupang inputs. Identity and observations never
 * come from a save request.
 */
public final class CoupangInputs {
    private static final ObjectMapper ROW_JSON = new ObjectMapper();
    private CoupangInputs() {
    }
    public static Document empty() {
        return new Document(new LinkedHashMap<>(),
                List.of(new Option(UUID.randomUUID().toString(), "",
                        Map.of("taxType", "TAX", "adultOnly", "EVERYONE", "unitCount", "1", "maximumBuyCount", "0"),
                        List.of(), List.of(), List.of(), List.of(), List.of(), null)),
                new LinkedHashMap<>(), new LinkedHashMap<>(), List.of());
    }
    public static Mapping mapping(String account, CoupangEditor.EditorDocument source, Document existing) {
        var links = new ArrayList<OptionMapping>();
        for (int n = 0; n < source.options().size(); n++) {
            var o = source.options().get(n);
            links.add(new OptionMapping(existing == null
                    ? stable(account + ":" + source.basic().sellerProductId() + ":" + o.sellerProductItemId())
                    : existing.options().get(n).id(), o.sellerProductItemId(), o.vendorItemId()));
        }
        return new Mapping(account, source.basic().sellerProductId(), List.copyOf(links));
    }
    public static Document from(CoupangEditor.EditorDocument source, Mapping mapping) {
        var b = source.basic();
        if (mapping.sellerProductId() != null && !Objects.equals(mapping.sellerProductId(), b.sellerProductId()))
            throw invalid("쿠팡 상품 ID가 변경되었습니다.");
        var basic = new LinkedHashMap<String, String>();
        basic.put("sellerProductName", text(b.sellerProductName()));
        basic.put("displayProductName", text(b.displayProductName()));
        basic.put("generalProductName", text(b.generalProductName()));
        basic.put("brand", text(b.brand()));
        basic.put("displayCategoryCode", text(b.displayCategoryCode()));
        basic.put("productGroup", text(b.productGroup()));
        var options = new ArrayList<Option>();
        if (source.options().size() != mapping.options().size())
            throw invalid("쿠팡 옵션 구성이 변경되었습니다. 다시 조회해 주세요.");
        for (var link : mapping.options()) {
            var o = source.options().stream()
                    .filter(v -> Objects.equals(v.sellerProductItemId(), link.sellerProductItemId())).findFirst()
                    .orElseThrow(() -> invalid("쿠팡 옵션 ID가 변경되었습니다."));
            if (link.vendorItemId() != null && !Objects.equals(link.vendorItemId(), o.vendorItemId()))
                throw invalid("쿠팡 옵션 ID가 변경되었습니다.");
            var registration = fields(o.registration());
            registration.keySet().retainAll(REGISTRATION);
            if (o.current() != null) {
                if (o.current().salePrice() != null)
                    registration.put("salePrice", o.current().salePrice().toString());
                if (o.current().amountInStock() != null)
                    registration.put("maximumBuyCount", o.current().amountInStock().toString());
            }
            var images = new ArrayList<Image>();
            int n = 0;
            for (var i : list(o.images()))
                images.add(new Image(stable(link.optionId() + ":image:" + n++), null, i.url(), i.type(), i.order()));
            var contents = new ArrayList<Content>();
            n = 0;
            for (var c : list(o.contents()))
                contents.add(new Content(stable(link.optionId() + ":content:" + n++), c.type(), c.detailType(),
                        c.content(), null));
            options.add(new Option(link.optionId(), o.itemName(), registration, list(o.attributes()), images, contents,
                    list(o.notices()), list(o.certifications()), null));
        }
        var delivery = fields(source.delivery());
        delivery.keySet().retainAll(DELIVERY);
        var settings = fields(source.settings());
        settings.keySet().retainAll(SETTINGS);
        return new Document(basic, options, delivery, settings, list(source.documents()));
    }
    /**
     * Preserve individual media identities while keeping the latest observed
     * values.
     */
    public static Document preserveMedia(Document fresh, Document previous, Map<String, String> tokens,
            Map<String, String> publicUrls) {
        var options = new ArrayList<Option>();
        for (var o : fresh.options()) {
            var prior = previous.options().stream().filter(v -> v.id().equals(o.id())).findFirst().orElse(null);
            if (prior == null) {
                options.add(o);
                continue;
            }
            var images = new ArrayList<Image>();
            var usedImages = new HashSet<String>();
            var priorImageIds = prior.images().stream().map(Image::id).collect(java.util.stream.Collectors.toSet());
            for (var image : o.images()) {
                var old = prior.images().stream()
                        .filter(v -> !usedImages.contains(v.id()) && (Objects.equals(v.url(), image.url())
                                || v.assetId() != null && tokens.containsKey(v.assetId()) && image.url() != null
                                        && image.url().endsWith("/coupang/images/" + tokens.get(v.assetId()))))
                        .sorted(Comparator.comparing(v -> !Objects.equals(v.type(), image.type()))).findFirst()
                        .orElse(null);
                if (old != null)
                    usedImages.add(old.id());
                images.add(old == null
                        ? new Image(priorImageIds.contains(image.id()) ? UUID.randomUUID().toString() : image.id(),
                                image.assetId(), image.url(), image.type(), image.order())
                        : new Image(old.id(),
                                Objects.equals(old.url(), image.url())
                                        || Objects.equals(publicUrls.get(old.assetId()), image.url())
                                                ? old.assetId()
                                                : null,
                                image.url(), image.type(), image.order()));
            }
            var contents = new ArrayList<Content>();
            var usedContents = new HashSet<String>();
            var priorContentIds = prior.contents().stream().map(Content::id)
                    .collect(java.util.stream.Collectors.toSet());
            for (var content : o.contents()) {
                var old = prior.contents().stream()
                        .filter(v -> !usedContents.contains(v.id()) && Objects.equals(v.type(), content.type())
                                && (Objects.equals(v.content(), content.content())
                                        || published(v.assetId(), content.content(), tokens)))
                        .sorted(Comparator.comparing(v -> !Objects.equals(v.detailType(), content.detailType())))
                        .findFirst().orElse(null);
                if (old != null)
                    usedContents.add(old.id());
                contents.add(old == null
                        ? new Content(
                                priorContentIds.contains(content.id()) ? UUID.randomUUID().toString() : content.id(),
                                content.type(), content.detailType(), content.content(), content.assetId())
                        : new Content(old.id(), content.type(), content.detailType(), content.content(),
                                Objects.equals(old.detailType(), content.detailType())
                                        && (Objects.equals(old.content(), content.content())
                                                || Objects.equals(publicUrls.get(old.assetId()), content.content()))
                                                        ? old.assetId()
                                                        : null));
            }
            options.add(new Option(o.id(), o.itemName(), o.registration(), o.attributes(), images, contents,
                    o.notices(), o.certifications(), o.noticeCategory()));
        }
        return new Document(fresh.basic(), List.copyOf(options), fresh.delivery(), fresh.settings(), fresh.documents());
    }
    private static boolean published(String asset, String value, Map<String, String> tokens) {
        return asset != null && tokens.containsKey(asset) && value != null
                && value.endsWith("/coupang/images/" + tokens.get(asset));
    }
    public static Mapping enrich(Mapping mapping, CoupangEditor.EditorDocument source) {
        from(source, mapping);
        var links = new ArrayList<OptionMapping>();
        for (var link : mapping.options()) {
            var o = source.options().stream()
                    .filter(v -> Objects.equals(v.sellerProductItemId(), link.sellerProductItemId())).findFirst()
                    .orElseThrow();
            links.add(new OptionMapping(link.optionId(), link.sellerProductItemId(), o.vendorItemId()));
        }
        return new Mapping(mapping.accountKey(), mapping.sellerProductId(), List.copyOf(links));
    }
    public static Map<String, String> fields(List<CoupangCatalog.Field> fields) {
        var out = new LinkedHashMap<String, String>();
        for (var f : list(fields))
            if (f.value() != null)
                out.put(f.name(), f.value());
        return out;
    }
    private static String stable(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private record Rows(String name, List<String> keys, Set<String> fields) {
    }
    private static final List<Rows> ROWS = List.of(new Rows("attributes", List.of("name", "exposed"), Set.of("value")),
            new Rows("notices", List.of("category", "name"), Set.of("content")),
            new Rows("certifications", List.of("type"), Set.of("code")),
            new Rows("documents", List.of("templateName"), Set.of("path", "vendorPath")));
    private static String localPath(CoupangEditing.Change c) {
        return c.path().startsWith("options.") ? c.path().substring(8) : c.path();
    }
    private static Rows rows(CoupangEditing.Change c) {
        String p = localPath(c);
        return ROWS.stream().filter(r -> p.equals(r.name()) || p.startsWith(r.name() + ".")).findFirst().orElse(null);
    }
    public static boolean legacyList(CoupangEditing.Change c) {
        return c.rowKey() == null && rows(c) != null;
    }
    public static void checkCurrent(CoupangEditing.Change c) {
        check(c);
        if (legacyList(c))
            throw invalid("이전 목록 수정의 범위를 확인할 수 없습니다. 입력을 보관하고 다시 준비해 주세요.");
    }
    private static ObjectNode optionRoot(ObjectNode root, CoupangEditing.Change c) {
        if (!c.path().startsWith("options."))
            return root;
        for (var o : root.path("options"))
            if (c.optionId().equals(o.path("id").asString()))
                return o.asObject();
        throw invalid("수정할 옵션 UUID를 확인해 주세요.");
    }
    private static int rowIndex(ArrayNode array, Rows spec, List<String> key) {
        int found = -1;
        for (int n = 0; n < array.size(); n++) {
            var row = array.get(n);
            boolean match = true;
            for (int k = 0; k < key.size(); k++)
                match &= key.get(k).equals(row.path(spec.keys().get(k)).asString());
            if (match) {
                if (found >= 0)
                    throw invalid("목록의 행 식별자가 중복되었습니다.");
                found = n;
            }
        }
        return found;
    }
    public static JsonNode value(ObjectMapper json, Document document, CoupangEditing.Change c) {
        check(c);
        var row = optionRoot(json.valueToTree(document).asObject(), c);
        String path = localPath(c);
        if (c.rowKey() != null) {
            var spec = rows(c);
            var array = row.path(spec.name()).asArray();
            int index = rowIndex(array, spec, c.rowKey());
            if (index < 0)
                return json.nullNode();
            return path.equals(spec.name())
                    ? array.get(index)
                    : array.get(index).path(path.substring(spec.name().length() + 1));
        }
        JsonNode out = row;
        for (String part : parts(path))
            out = out.path(part);
        return out;
    }
    public static boolean matches(ObjectMapper json, Document d, CoupangEditing.Change c) {
        var v = value(json, d, c);
        return c.action().equals("REMOVE") ? v.isNull() : v.equals(json.valueToTree(c.value()));
    }
    public static boolean sameChange(ObjectMapper json, CoupangEditing.Change a, CoupangEditing.Change b) {
        return Objects.equals(a.path(), b.path()) && Objects.equals(a.optionId(), b.optionId())
                && Objects.equals(a.rowKey(), b.rowKey()) && a.action().equals(b.action())
                && json.valueToTree(a.value()).equals(json.valueToTree(b.value()));
    }
    public static Document apply(ObjectMapper json, Document document, List<CoupangEditing.Change> changes) {
        if (changes == null || changes.size() > 500)
            throw invalid("수정 항목을 확인해 주세요.");
        var root = json.valueToTree(document).asObject();
        var seen = new HashSet<String>();
        for (var c : changes) {
            check(c);
            if (!seen.add(c.path() + "|" + c.optionId() + "|" + json.writeValueAsString(c.rowKey())))
                throw invalid("수정 항목이 중복되었습니다.");
            var row = optionRoot(root, c);
            String path = localPath(c);
            if (c.rowKey() != null) {
                var spec = rows(c);
                var array = row.path(spec.name()).asArray();
                int index = rowIndex(array, spec, c.rowKey());
                if (c.action().equals("REMOVE")) {
                    if (index >= 0)
                        array.remove(index);
                    continue;
                }
                if (path.equals(spec.name())) {
                    var wanted = json.valueToTree(c.value());
                    if (index >= 0)
                        array.set(index, wanted);
                    else
                        array.add(wanted);
                } else {
                    ObjectNode wanted;
                    if (index >= 0)
                        wanted = array.get(index).asObject();
                    else {
                        wanted = array.addObject();
                        for (int k = 0; k < c.rowKey().size(); k++)
                            wanted.put(spec.keys().get(k), c.rowKey().get(k));
                        if (spec.name().equals("certifications"))
                            wanted.putArray("attachments");
                    }
                    wanted.set(path.substring(spec.name().length() + 1), json.valueToTree(c.value()));
                }
                continue;
            }
            String[] parts = parts(path);
            for (int n = 0; n < parts.length - 1; n++) {
                if (!row.path(parts[n]).isObject())
                    throw invalid("수정 필드를 확인해 주세요.");
                row = row.path(parts[n]).asObject();
            }
            row.set(parts[parts.length - 1], json.valueToTree(c.value()));
        }
        return normalize(json.treeToValue(root, Document.class), json);
    }
    public static void check(CoupangEditing.Change c) {
        if (c == null || c.path() == null)
            throw invalid("수정 필드를 확인해 주세요.");
        String p = c.path();
        boolean option = p.startsWith("options.");
        if (option ? !uuid(c.optionId()) : c.optionId() != null)
            throw invalid("수정 옵션 UUID를 확인해 주세요.");
        if (!Set.of("SET", "REMOVE").contains(c.action()))
            throw invalid("수정 동작을 확인해 주세요.");
        var spec = rows(c);
        if (c.rowKey() != null) {
            if (spec == null || option == spec.name().equals("documents") || c.rowKey().size() != spec.keys().size()
                    || c.rowKey().stream().anyMatch(k -> k == null || k.isBlank() || k.length() > 300))
                throw invalid("수정 행 식별자를 확인해 주세요.");
            String local = localPath(c);
            if (!local.equals(spec.name()) && !spec.fields().contains(local.substring(spec.name().length() + 1)))
                throw invalid("수정 행 필드를 확인해 주세요.");
            if (c.action().equals("REMOVE") && (!local.equals(spec.name()) || c.value() != null))
                throw invalid("삭제할 행을 확인해 주세요.");
            if (c.action().equals("SET") && local.equals(spec.name())) {
                var v = ROW_JSON.valueToTree(c.value());
                var allowed = new HashSet<>(spec.keys());
                allowed.addAll(spec.fields());
                if (spec.name().equals("certifications"))
                    allowed.add("attachments");
                if (!v.isObject() || !allowed.containsAll(v.propertyNames())
                        || spec.name().equals("certifications") && v.path("attachments").size() > 0)
                    throw invalid("수정 행 값을 확인해 주세요.");
                for (int k = 0; k < c.rowKey().size(); k++)
                    if (!c.rowKey().get(k).equals(v.path(spec.keys().get(k)).asString()))
                        throw invalid("수정 행 식별자가 일치하지 않습니다.");
            }
            return;
        }
        if (!c.action().equals("SET"))
            throw invalid("목록 행만 삭제할 수 있습니다.");
        boolean allowed = p.startsWith("basic.") && BASIC.contains(p.substring(6))
                || p.startsWith("settings.") && SETTINGS.contains(p.substring(9))
                || p.startsWith("delivery.") && DELIVERY.contains(p.substring(9))
                || p.startsWith("options.registration.") && REGISTRATION.contains(p.substring(21))
                || Set.of("options.itemName", "options.attributes", "options.images", "options.contents",
                        "options.notices", "options.certifications", "documents").contains(p);
        if (!allowed)
            throw invalid("지원하지 않는 수정 필드입니다: " + p);
    }
    public static void verify(ObjectMapper json, Document before, Document after, List<CoupangEditing.Change> changes,
            boolean create) {
        normalize(after, json);
        for (var c : changes)
            checkCurrent(c);
        if (create)
            return;
        var applied = apply(json, before, changes);
        if (!json.valueToTree(applied).equals(json.valueToTree(after)))
            throw invalid("문서와 수정 항목이 일치하지 않습니다. 입력을 유지한 채 다시 준비해 주세요.");
    }
    private static String[] parts(String path) {
        if (path.startsWith("registration."))
            return new String[]{"registration", path.substring(13)};
        if (path.startsWith("settings."))
            return new String[]{"settings", path.substring(9)};
        return path.split("\\.");
    }
    private static InputValidationFailure invalid(String m) {
        return new InputValidationFailure(m);
    }
}
