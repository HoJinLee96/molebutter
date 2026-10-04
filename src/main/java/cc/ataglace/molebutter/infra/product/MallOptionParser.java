package cc.ataglace.molebutter.infra.product;

import java.util.*;
import java.util.regex.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import static cc.ataglace.molebutter.infra.product.NaverSearchPayload.*;

/** 외부 스크립트를 실행하지 않는다. 확인된 상품의 옵션 컨테이너만 읽고, 필드 누락은 null로 유지한다. */
public final class MallOptionParser {
    private final ObjectMapper json;

    public MallOptionParser(ObjectMapper json) {
        this.json = json;
    }

    public List<SourceOption> parse(ProcurementMall mall, String payload, String productId) {
        return parseOptions(mall, payload, productId).options();
    }

    private ParsedOptions parseOptions(ProcurementMall mall, String payload, String productId) {
        if (mall == ProcurementMall.HAZZYS)
            return hazzys(payload, productId).finish();
        if (mall == ProcurementMall.LOTTE_IMALL)
            return imall(payload, productId).finish();
        var out = new OptionSet();
        for (JsonNode root : roots(mall, payload))
            switch (mall) {
                case LOTTE_ON -> {
                    if (!LotteProductPayload.matches(root, productId))
                        break;
                    var inventory = LotteProductPayload.inventory(root, productId);
                    out.found = true;
                    out.complete &= inventory.complete();
                    out.options.addAll(inventory.options());
                }
                case HMALL -> {
                    if (!"Y".equals(text(root, "successYn")))
                        break;
                    var data = root.path("respData");
                    var rows = data.path("attrs");
                    if (!rows.isArray() || (!data.path("combAttrs").isNull()
                            && !data.path("combAttrs").isMissingNode())) {
                        out.complete = false; // 조합형의 조합별 수량 없는 응답은 미지원
                        break;
                    }
                    for (JsonNode n : rows)
                        if (productId.equals(text(n, "slitmCd"))) {
                            out.found = true;
                            out.options.add(option(text(n, "uitmCd"), text(n, "uitmTotNm"), number(n, "stck")));
                        } else {
                            out.complete = false;
                        }
                }
                case LFMALL -> {
                    var data = root.path("body").path("productOptionDTO");
                    if (!productId.equals(text(data, "productCode")))
                        break;
                    out.found = true;
                    var rows = data.path("productOptionSizeDTOList");
                    if (!rows.isArray() || rows.isEmpty()) {
                        out.complete = false;
                        break;
                    }
                    for (JsonNode n : rows) {
                        Long stock = number(n, "currentStockQuantity");
                        if ("Y".equals(text(n, "soldoutYn")))
                            stock = 0L;
                        var opt = option(text(n, "sizeCode", "optionSizeCode", "productOptionSizeCode"),
                                text(n, "sizeName", "sizeNm", "sizeValue"), stock);
                        out.options.add("N".equals(text(n, "saleAbleYn")) && !Objects.equals(stock, 0L)
                                ? unavailable(opt) : opt);
                    }
                }
                case NAVER_SMART_STORE -> {
                    if (!SupplierMetadataParser.naverMatches(root, productId))
                        break;
                    var inventory = NaverProductInventory.read(root, productId);
                    out.found = true;
                    out.complete &= inventory.complete();
                    out.options.addAll(inventory.options());
                }
                case HI_THEHYUNDAI -> hi(root, productId, out, 0);
                default -> {
                }
            }
        return out.finish();
    }

    private record ParsedOptions(List<SourceOption> options, boolean complete) {}

    private static final class OptionSet {
        final List<SourceOption> options = new ArrayList<>();
        boolean found;
        boolean complete = true;

        ParsedOptions finish() {
            // 모든 매입처에 같은 중복 정책을 적용한다. 충돌한 옵션은 순서에 따라 선택하지 않는다.
            Map<String, SourceOption> unique = new LinkedHashMap<>();
            Set<String> ambiguous = new HashSet<>();
            for (var o : options) {
                if (o.id().isBlank() || o.label().isBlank()) {
                    complete = false;
                    continue;
                }
                var before = unique.putIfAbsent(o.id(), o);
                if (before != null && !before.equals(o))
                    ambiguous.add(o.id());
            }
            ambiguous.forEach(unique::remove);
            return new ParsedOptions(List.copyOf(unique.values()),
                    found && complete && ambiguous.isEmpty() && !unique.isEmpty());
        }
    }

    private List<JsonNode> roots(ProcurementMall mall, String payload) {
        List<JsonNode> roots = new ArrayList<>();
        try {
            roots.add(json.readTree(payload));
        } catch (Exception ignored) {
            Matcher scripts = Pattern.compile(
                    "<script[^>]*(?:type=[\"']application/(?:ld\\+)?json[\"']|id=[\"']__NEXT_DATA__[\"'])[^>]*>(.*?)</script>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(payload);
            while (scripts.find())
                try {
                    roots.add(json.readTree(scripts.group(1)));
                } catch (Exception ignored2) {
                }
            if (mall == ProcurementMall.HI_THEHYUNDAI) {
                StringBuilder flight = new StringBuilder();
                Matcher chunks = Pattern.compile("self\\.__next_f\\.push\\((.*?)\\)</script>", Pattern.DOTALL)
                        .matcher(payload);
                while (chunks.find())
                    try {
                        var a = json.readTree(chunks.group(1));
                        if (a.path(1).isString())
                            flight.append(a.path(1).asText());
                    } catch (Exception ignored2) {
                    }
                // Flight의 JSON 배열 프레임만 해석한다. HTML/JS 문자열은 실행하지 않는다.
                Matcher frames = Pattern.compile("[0-9a-f]+:(\\[.*?)(?=\\n[0-9a-f]+:|$)", Pattern.DOTALL)
                        .matcher(flight);
                while (frames.find())
                    try {
                        roots.add(json.readTree(frames.group(1)));
                    } catch (Exception ignored2) {
                    }
            }
        }
        return roots;
    }

    public SourceDetails details(ProcurementMall mall, String payload, String productId) {
        String title = "", model = "", brand = "", store = "";
        if (mall == ProcurementMall.NAVER_SMART_STORE) {
            var rs = roots(mall, payload);
            var evidence = SupplierMetadataParser.parse(mall, rs, productId);
            var matching = rs.stream().filter(r -> SupplierMetadataParser.naverMatches(r, productId)).distinct()
                    .toList();
            if (matching.size() != 1)
                return new SourceDetails("", "", "", List.of(), evidence == null ? "" : evidence.name(), evidence,
                        false);
            var root = matching.getFirst();
            var body = root.path("contents").isObject() ? root.path("contents") : root;
            title = text(body, "name", "productName");
            model = text(body.path("naverShoppingSearchInfo"), "modelName");
            if (model.isBlank())
                model = text(body, "modelName", "modelCode");
            var inventory = NaverProductInventory.read(root, productId);
            return new SourceDetails(title, model, "", inventory.options(), evidence == null ? "" : evidence.name(),
                    evidence, inventory.complete(), NaverChannelPolicy.response(root, productId));
        }
        for (var root : roots(mall, payload)) {
            JsonNode main = null;
            if (mall == ProcurementMall.LFMALL) {
                main = root.path("body").path("productBasicDTO");
                if (productId.equals(text(main, "productCode")))
                    model = text(main, "productCode");
                else
                    main = null;
            }
            if (mall == ProcurementMall.NAVER_SMART_STORE && productId.equals(text(root, "id", "channelProductNo")))
                main = root;
            if (mall == ProcurementMall.LOTTE_ON) {
                var data = root.path("data");
                var basic = data.path("basicInfo");
                if (LotteProductPayload.matches(root, productId)) {
                    main = basic;
                    store = text(basic, "lrtrNm", "trNm");
                    if (store.isBlank())
                        store = text(data.path("slrInfo").path("trBase"), "lrtrNm", "trNm");
                }
            }
            if (mall == ProcurementMall.HI_THEHYUNDAI)
                main = mainProduct(root, productId, 0);
            if (main != null) {
                title = text(main, "productName", "name", "pdNm", "sitmNm", "slitmNm");
                if (model.isBlank())
                    model = text(main, "modelName", "modelCode", "mdlNm", "mdlNo");
                brand = text(main, "brandName", "brandNm");
                if (store.isBlank())
                    store = text(main, "branchName", "storeName", "lrtrNm", "trNm", "shopNm");
                break;
            }
        }
        if (mall == ProcurementMall.HAZZYS && productId.equals(input(payload, "CARTITEMCD"))) {
            model = productId;
            title = input(payload, "PRODNAME");
        }
        var parsed = parseOptions(mall, payload, productId);
        var options = parsed.options();
        // 페이지 자체의 제목 메타데이터만 읽고 추천 상품이나 전체 본문을 지점 근거로 쓰지 않는다.
        if (title.isBlank() && (mall == ProcurementMall.HAZZYS || mall == ProcurementMall.LOTTE_IMALL || mall == ProcurementMall.HI_THEHYUNDAI)
                && !options.isEmpty())
            title = pageTitle(payload);
        var evidence = SupplierMetadataParser.parse(mall, roots(mall, payload), productId);
        if (evidence != null && store.isBlank())
            store = evidence.name();
        return new SourceDetails(title, model, brand, options, store, evidence, parsed.complete());
    }

    private static String pageTitle(String html) {
        var m = Pattern.compile("<meta\\b([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String attrs = m.group(1);
            if ("og:title".equals(attribute(attrs, "property")))
                return org.springframework.web.util.HtmlUtils.htmlUnescape(attribute(attrs, "content"));
        }
        return "";
    }

    private static JsonNode mainProduct(JsonNode node, String id, int depth) {
        if (depth > 18)
            return null;
        if (node.isObject() && node.has("slitmCd"))
            return id.equals(text(node, "slitmCd")) && node.path("sellUitmList").isArray() ? node : null;
        for (JsonNode c : node)
            if (c.isObject() || c.isArray()) {
                var match = mainProduct(c, id, depth + 1);
                if (match != null)
                    return match;
            }
        return null;
    }

    private static SourceOption option(String id, String label, Long stock) {
        return new SourceOption(id, label, stock,
                stock == null ? "STOCK_UNKNOWN" : stock == 0 ? "SOLD_OUT" : "AVAILABLE");
    }

    private static SourceOption unavailable(SourceOption o) {
        return new SourceOption(o.id(), o.label(), o.stock(), "UNAVAILABLE");
    }

    private static void hi(JsonNode node, String productId, OptionSet out, int depth) {
        if (depth > 18)
            return;
        if (node.isObject() && node.has("slitmCd")) {
            // 본상품 경계 안의 추천 상품을 요청한 상품의 응답으로 사용하지 않는다.
            if (!productId.equals(text(node, "slitmCd")))
                return;
            out.found = true;
            var rows = node.path("sellUitmList");
            if (!rows.isArray() || rows.isEmpty()) {
                out.complete = false;
                return;
            }
            for (JsonNode n : rows)
                out.options.add(option(text(n, "uitmCd"), text(n, "uitmTotNm"), number(n, "sellPossQty")));
            return;
        }
        for (JsonNode c : node)
            if (c.isObject() || c.isArray())
                hi(c, productId, out, depth + 1);
    }

    private static OptionSet hazzys(String html, String productId) {
        var out = new OptionSet();
        if (!productId.equals(input(html, "CARTITEMCD")))
            return out;
        out.found = true;
        String color = capture(html, "<dd[^>]*class=\"colorWrapHnm\"[^>]*>([^<]+)</dd>");
        Matcher buttons = Pattern.compile("<button\\b([^>]+)>([^<]*)</button>").matcher(html);
        while (buttons.find()) {
            String attrs = buttons.group(1);
            if (!("radioChkSize" + productId).equals(attribute(attrs, "name")))
                continue;
            boolean disabled = Pattern.compile("(?:^|\\s)disabled(?:\\s|=|$)").matcher(attrs).find()
                    || "true".equals(attribute(attrs, "aria-disabled"));
            String size = attribute(attrs, "data-size");
            Long stock = integer(attribute(attrs, "value"));
            String label = buttons.group(2).trim();
            var opt = option(size, label.isBlank() ? "" : color + " / " + label, stock);
            out.options.add(disabled && !Objects.equals(stock, 0L) ? unavailable(opt) : opt);
        }
        return out;
    }

    private static OptionSet imall(String html, String productId) {
        var out = new OptionSet();
        Matcher groups = Pattern.compile("itemInfo\\[\\d+\\]\\s*=\\s*\\{(.*?)\\};", Pattern.DOTALL).matcher(html);
        while (groups.find()) {
            String group = groups.group(1);
            if (!productId.equals(capture(group, "goods_no\\s*:\\s*['\"]?([0-9]+)")))
                continue;
            out.found = true;
            int before = out.options.size();
            Matcher items = Pattern.compile("\\{([^{}]*\\bitem_no\\s*:[^{}]*)}", Pattern.DOTALL).matcher(group);
            while (items.find()) {
                String row = items.group(1), id = capture(row, "item_no\\s*:\\s*['\"]?([0-9]+)"),
                        label = capture(row, "opt_nm\\s*:\\s*'([^']*)'");
                if (label.isBlank() && "1".equals(capture(group, "item_count\\s*:\\s*([0-9]+)")))
                    label = "단일상품";
                Long stock = integer(capture(row, "inv_qty\\s*:\\s*([0-9]+)"));
                out.options.add(option(id, label, stock));
            }
            Long count = integer(capture(group, "item_count\\s*:\\s*([0-9]+)"));
            out.complete &= count != null && count == out.options.size() - before;
        }
        return out;
    }

    private static String input(String html, String name) {
        Matcher m = Pattern.compile("<input\\b([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find())
            if (name.equals(attribute(m.group(1), "id")))
                return attribute(m.group(1), "value");
        return "";
    }

    private static String attribute(String attrs, String name) {
        return capture(attrs, "(?:^|\\s)" + Pattern.quote(name) + "=[\"']([^\"']*)[\"']");
    }

    private static String capture(String s, String regex) {
        var m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1).trim() : "";
    }

    private static Long integer(String s) {
        try {
            long n = Long.parseLong(s);
            return n < 0 ? null : n;
        } catch (Exception e) {
            return null;
        }
    }
}
