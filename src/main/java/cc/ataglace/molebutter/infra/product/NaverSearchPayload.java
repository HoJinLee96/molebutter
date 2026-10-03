package cc.ataglace.molebutter.infra.product;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;

/** 화면 동작과 응답의 검색어·정렬·페이지를 대조한 뒤에만 가격으로 사용한다. */
final class NaverSearchPayload {
    private final ObjectMapper json;

    NaverSearchPayload(ObjectMapper json) {
        this.json = json;
    }

    record Captured(SearchResult result, int pageSize, Long total, boolean smartPrice) {
    }

    Captured match(String url, String payload, String query, int page, int size, Boolean smartPrice) {
        if (!isSearchApi(url))
            return null;
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || !root.isObject())
            return null;
        JsonNode param = root.path("searchParam"), result = root.path("shoppingResult");
        if (!result.path("products").isArray() || text(root, "requestId").isBlank()
                || !root.path("appliedSmartPriceSort").isBoolean()
                || !query.equalsIgnoreCase(text(param, "query")) || !"price_asc".equals(text(param, "sort"))
                || !Objects.equals(number(param, "pagingIndex"), (long) page)
                || !Objects.equals(number(param, "pagingSize"), (long) size)
                || !query.equalsIgnoreCase(parameter(url, "query")) || !"price_asc".equals(parameter(url, "sort"))
                || !Integer.toString(page).equals(parameter(url, "pagingIndex"))
                || !Integer.toString(size).equals(parameter(url, "pagingSize")))
            return null;
        boolean observedSmart = root.path("appliedSmartPriceSort").asBoolean();
        if (smartPrice != null && smartPrice != observedSmart)
            return null;
        Long total = number(result, "total", "totalCount");
        if (total == null)
            total = number(root, "total", "totalCount");
        boolean complete = result.has("hasNext") ? !result.path("hasNext").asBoolean(true)
                : total != null && (long) page * size >= total;
        return new Captured(new SearchResult(offers(result.path("products")), complete, null), size, total,
                observedSmart);
    }

    List<String> validationErrors(String url, String body, String query, int page, int size, Boolean smart) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            return List.of("JSON_FORMAT");
        }
        if (root == null || !root.isObject())
            return List.of("JSON_FORMAT");
        var errors = new ArrayList<String>();
        var param = root.path("searchParam");
        if (!root.path("shoppingResult").path("products").isArray() || text(root, "requestId").isBlank()
                || !root.path("appliedSmartPriceSort").isBoolean())
            errors.add("SCHEMA");
        if (!query.equalsIgnoreCase(text(param, "query")) || !query.equalsIgnoreCase(parameter(url, "query")))
            errors.add("QUERY");
        if (!"price_asc".equals(text(param, "sort")) || !"price_asc".equals(parameter(url, "sort")))
            errors.add("SORT");
        if (!Objects.equals(number(param, "pagingIndex"), (long) page)
                || !Integer.toString(page).equals(parameter(url, "pagingIndex")))
            errors.add("PAGE");
        if (!Objects.equals(number(param, "pagingSize"), (long) size)
                || !Integer.toString(size).equals(parameter(url, "pagingSize")))
            errors.add("PAGE_SIZE");
        if (smart != null && root.path("appliedSmartPriceSort").asBoolean() != smart)
            errors.add("SMART_PRICE");
        return List.copyOf(errors);
    }

    SearchResult parse(String payload) {
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("검색 JSON 형식 오류");
        }
        JsonNode container = locate(root, 0);
        if (container == null)
            throw new IllegalArgumentException("검색 상품 목록 없음");
        return new SearchResult(offers(container.path("products")),
                container.has("hasNext") && !container.path("hasNext").asBoolean(true), null);
    }

    private List<Offer> offers(JsonNode products) {
        List<Offer> offers = new ArrayList<>();
        for (JsonNode entry : products) {
            JsonNode p = entry.has("item") ? entry.path("item") : entry;
            String mall = text(p, "mallName"), nv = text(p, "nvMid", "naverProductId", "id");
            if ("naver_model".equals(text(p, "mallId")) || p.path("catalog").asBoolean(false) || nv.isBlank()
                    || mall.isBlank())
                continue;
            String url = productUrl(p);
            Mall source = ProductSourceMetadata.mall(url);
            String supplied = text(p, "mallProductId", "channelProductId", "chnlProdNo");
            String productId = ProductSourceMetadata.productId(source, url, supplied);
            // 충돌한 후보를 빈 번호로 남기면 후속 호출이 URL에서 번호를 다시 확정할 수 있다.
            if (source != null && productId.isBlank() && !supplied.isBlank()
                    && !ProductSourceMetadata.productId(source, url, "").isBlank())
                continue;
            offers.add(new Offer(nv, text(p, "productName", "productTitle"), mall,
                    productId,
                    url, number(p, "price", "discountedSalePrice", "salePrice", "lowPrice"),
                    number(p, "deliveryFee", "dlvryFee", "krwDlvryFee"), source,
                    ProductSourceMetadata.imageUrl(text(p, "imageUrl", "image", "image_url")),
                    source == Mall.NAVER_SMART_STORE ? channelHints(p) : null,
                    source == Mall.NAVER_SMART_STORE
                            ? new SearchStoreEvidence(text(p, "chnlSeq"), text(p.path("channelInfoCache"), "chnlSeq"),
                                    text(p.path("channelInfoCache"), "chnlName"), text(p, "comNm"), text(p, "wdTp"),
                                    text(p, "wdNm"))
                            : null));
        }
        return List.copyOf(offers);
    }

    private static NaverChannel channelHints(JsonNode product) {
        NaverChannel result = NaverChannelPolicy.unknown();
        Set<String> ids = new HashSet<>();
        for (String key : List.of("purchaseUrl", "mallProductUrl", "link", "crUrl")) {
            String url = text(product, key);
            if (ProductSourceMetadata.mall(url) != Mall.NAVER_SMART_STORE)
                continue;
            result = NaverChannelPolicy.merge(result, NaverChannelPolicy.fromUrl(url));
            String id = ProductSourceMetadata.productId(Mall.NAVER_SMART_STORE, url, "");
            if (!id.isBlank())
                ids.add(id);
        }
        String supplied = text(product, "mallProductId", "channelProductId", "chnlProdNo");
        if (!supplied.isBlank())
            ids.add(supplied);
        return ids.size() > 1 ? new NaverChannel(NaverChannelType.CONFLICT, null) : result;
    }

    private static String productUrl(JsonNode product) {
        List<String> urls = List.of("purchaseUrl", "mallProductUrl", "link", "crUrl").stream()
                .map(key -> text(product, key)).filter(value -> !value.isBlank()).toList();
        // 추적 링크보다 실제 지원 매입처 링크가 있으면 그 주소를 사용한다.
        return urls.stream().filter(value -> ProductSourceMetadata.mall(value) != null).findFirst()
                .orElseGet(() -> urls.stream().findFirst().orElse(""));
    }

    static boolean isSearchApi(String url) {
        try {
            URI u = URI.create(url);
            return "search.shopping.naver.com".equals(u.getHost()) && "/api/search/all".equals(u.getPath());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String parameter(String url, String name) {
        try {
            String raw = URI.create(url).getRawQuery();
            if (raw == null)
                return "";
            for (String part : raw.split("&")) {
                String[] p = part.split("=", 2);
                if (name.equals(p[0]))
                    return URLDecoder.decode(p.length == 2 ? p[1] : "", StandardCharsets.UTF_8);
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static JsonNode locate(JsonNode node, int depth) {
        if (node == null || depth > 12)
            return null;
        if (node.isObject() && node.path("products").isArray())
            return node;
        for (JsonNode child : node)
            if (child.isObject() || child.isArray()) {
                var found = locate(child, depth + 1);
                if (found != null)
                    return found;
            }
        return null;
    }

    static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (v.isValueNode() && !v.isNull() && !v.asText().isBlank())
                return v.asText();
        }
        return "";
    }

    static Long number(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (v.isNull() || v.isMissingNode())
                continue;
            try {
                long n = new java.math.BigDecimal(v.asText()).longValueExact();
                if (n >= 0)
                    return n;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }
}
