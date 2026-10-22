package cc.ataglace.molebutter.coupang.internal;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.*;

/** Bounded diagnostic projection only. Never persist the upstream body or echo request values. */
public final class CoupangRejectionDetails {
    private CoupangRejectionDetails() {}
    public static final int MAX_LENGTH = 360; // Existing result_message is VARCHAR(500), including fixed guidance.
    private static final String HIDDEN = "[비공개]";
    private static final Pattern PRIVATE = Pattern.compile(
            "(?i)authorization|cookie|bearer|hmac|signature|access.?key|secret|password|passwd|token|api.?key|"
            + "vendor.?id|vendor.?user|business.?number|registration.?number|phone|mobile|contact|email|e-mail|address|zip.?code|"
            + "비밀번호|인증키|서명|주민|사업자.?번호|연락처|전화|이메일|주소|수취인|수령인|성명");
    private static final Set<String> LABELS = Set.of("attributeTypeName", "noticeCategoryName",
            "noticeCategoryDetailName", "certificationType", "imageType", "contentsType", "detailType");
    private static final Set<String> ENUMS = Set.of("true", "false", "SUCCESS", "ERROR", "NONE", "FREE",
            "TAX", "HTML", "TEXT", "IMAGE", "EXPOSED", "NOT_EXPOSED", "NOT_PARALLEL_IMPORTED",
            "PARALLEL_IMPORTED", "OVERSEAS_PURCHASED", "NOT_OVERSEAS_PURCHASED", "EVERYONE", "ADULT_ONLY");

    public static String extract(ObjectMapper json, JsonNode body, String requestJson) {
        var values = new TreeSet<String>(Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
        if (requestJson != null) {
            try { requestValues(json.readTree(requestJson), values); }
            catch (RuntimeException invalid) { return null; } // No unsafe fallback without request context.
        }
        var lines = new LinkedHashSet<String>();
        // The envelope may wrap a second response; do not traverse arbitrary seller data.
        addResponse(body, values, lines);
        if (body != null && body.path("data").isObject()) addResponse(body.path("data"), values, lines);
        return clean(String.join("\n", lines));
    }
    private static void requestValues(JsonNode n, Set<String> values) {
        if (n == null) return;
        if (n.isObject()) for (var entry : n.properties()) {
            if (!LABELS.contains(entry.getKey())) requestValues(entry.getValue(), values);
        }
        else if (n.isArray()) for (var row : n) requestValues(row, values);
        else if (n.isString()) {
            String value = normalize(n.asString());
            if (!value.isBlank() && !ENUMS.contains(value)
                    && (!value.matches("-?[0-9]+(?:\\.[0-9]+)?") || value.length() >= 5)) values.add(value);
        }
        else if (n.isNumber() && n.asString().length() >= 5) values.add(n.asString());
    }
    private static void addResponse(JsonNode n, Set<String> values, Set<String> lines) {
        if (n == null || !n.isObject()) return;
        for (String field : List.of("message", "errorMessage")) add(n.path(field), "", values, lines);
        var items = n.path("errorItems");
        if (items.isArray()) for (int i = 0; i < Math.min(items.size(), 10); i++) {
            var item = items.get(i);
            String prefix = "옵션 " + (item.path("itemIndex").isInt() && item.path("itemIndex").asInt() >= 0
                    && item.path("itemIndex").asInt() < 10000 ? item.path("itemIndex").asInt() + 1 : i + 1) + " · ";
            add(item.path("message"), prefix, values, lines);
            var attrs = item.path("itemAttributes");
            if (attrs.isArray()) for (int j = 0; j < Math.min(attrs.size(), 10); j++) {
                var a = attrs.get(j);
                String name = safe(a.path("attributeTypeName").asString(""), values);
                add(a.path("message"), prefix + (name.isBlank() ? "" : name + ": "), values, lines);
            }
        }
        // Keep actionable option errors ahead of long general explanations within the DB display limit.
        add(n.path("details"), "", values, lines);
    }
    private static void add(JsonNode n, String prefix, Set<String> values, Set<String> lines) {
        if (!n.isString() || lines.size() >= 16) return;
        String text = safe(n.asString(), values);
        if (!text.isBlank() && !Set.of("[]", "{}", "null").contains(text)) lines.add(prefix + text);
    }
    private static String safe(String value, Set<String> values) {
        if (value.length() > 8192) return "[긴 오류 설명 생략]";
        String text = normalize(value);
        // Remove complete URLs before examining labels: a token query must not hide the useful error sentence.
        text = text.replaceAll("(?i)(?:https?://|www\\.)[^\\s<>\"']+", HIDDEN);
        // Labels identifying credentials/PII suppress the entire fragment, not just an assumed value shape.
        if (PRIVATE.matcher(text).find()) return "[개인정보·인증정보가 포함된 설명 생략]";
        for (String input : values) text = text.replace(input, HIDDEN);
        text = text.replaceAll("(?i)(?:https?://|www\\.)[^\\s<>\"']+", HIDDEN)
                .replaceAll("[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}.\\-]+\\.[\\p{L}]{2,}", HIDDEN)
                .replaceAll("(?<![0-9])\\+?\\d[\\d ().-]{6,}\\d(?![0-9])", HIDDEN)
                .replaceAll("[A-Za-z0-9_+/=.-]{24,}", HIDDEN)
                .replaceAll("[<>]", "");
        return text.trim();
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\p{Cc}\\p{Cf}\\p{Z}]+", " ").trim();
    }
    /** Reapply generic redaction at persistence/read boundaries, before truncation. */
    public static String clean(String details) {
        if (details == null || details.isBlank()) return null;
        String text = String.join("\n", details.lines().limit(16).map(s -> safe(s, Set.of())).toList());
        if (text.length() > MAX_LENGTH) {
            int end = MAX_LENGTH - 1;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = text.substring(0, end) + "…";
        }
        return text.isBlank() ? null : text;
    }
}
