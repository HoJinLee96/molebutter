package cc.ataglace.molebutter.coupang.internal.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Only explicit rejections may repeat a write. Never infer rejection from a broken response. */
final class CoupangRetryPolicy {
    private CoupangRetryPolicy() {}

    static String reason(ObjectMapper json, String path, CoupangHttpTransport.Response response, boolean read) {
        int status = response.status();
        if (status == 429) return "RATE_LIMIT";
        if (read && (status == 408 || status >= 500 && status <= 599)) return "UPSTREAM";
        if (status != 200 && status != 400) return null;
        try {
            JsonNode root = json.readTree(response.body());
            if (root == null || !root.isObject()) return null;
            JsonNode outcome = "200".equals(root.path("code").asString()) && root.path("data").isObject()
                    ? root.path("data") : root;
            // HTTP 200 is not itself a rejection. A returned identifier is ambiguous even with ERROR.
            if (status == 200 && !"ERROR".equals(outcome.path("code").asString())) return null;
            if (!read && !outcome.path("data").isNull() && !outcome.path("data").isMissingNode()) return null;
            String message = outcome.path("message").asString("");
            if (message.startsWith("상품 정보가 등록 또는 수정되고 있습니다.")) return "PRODUCT_PROCESSING";
            String compact = message.replaceAll("[\\s'\"‘’“”]", "");
            if (path.endsWith("/approvals") && (
                    compact.equals("임시저장상태의상품만승인요청가능합니다.") ||
                    compact.equals("임시저장,승인완료,승인반려,부분승인완료상태의상품만승인요청가능합니다.")))
                return "APPROVAL_NOT_READY";
        } catch (RuntimeException ignored) {
            // Malformed or unknown responses are never evidence that a write was rejected.
        }
        return null;
    }

    static String operation(String method, String path) {
        if (path.endsWith("/categorization/predict")) return "CATEGORY_PREDICTION";
        if (path.endsWith("/status") && path.contains("/display-categories/")) return "CATEGORY_VALIDITY";
        if (path.endsWith("/approvals")) return "APPROVAL";
        if (path.contains("/prices/")) return "PRICE";
        if (path.contains("/quantities/")) return "STOCK";
        if (path.endsWith("/inventories")) return "INVENTORY";
        if (path.endsWith("/sales/stop")) return "SALE_STOP";
        if (path.endsWith("/sales/resume")) return "SALE_RESUME";
        if (path.contains("/shipping-place/outbound")) return "OUTBOUND_SHIPPING";
        if (path.contains("/returnShippingCenters")) return "RETURN_SHIPPING";
        if (path.contains("/returnWithdraw")) return "RETURN_WITHDRAWAL";
        if (path.contains("/returnRequests")) return "RETURN";
        if (path.contains("/exchangeRequests")) return "EXCHANGE";
        if (path.contains("/vendors/")) return "ORDER";
        if (path.equals(CoupangProductClient.PATH)) return method.equals("POST") ? "CREATE" : "PRODUCT";
        if (path.contains("/vendor-items/")) return "OPTION";
        if (method.equals("DELETE") && path.startsWith(CoupangProductClient.PATH + "/")) return "DELETE";
        if (path.startsWith(CoupangProductClient.PATH + "/")) return "PRODUCT_DETAIL";
        if (path.contains("/category-related-metas/")) return "CATEGORY_RULES";
        if (path.contains("/display-categories")) return "CATEGORY_LIST";
        if (path.contains("/brand")) return "BRAND";
        return "CATALOG";
    }
}
