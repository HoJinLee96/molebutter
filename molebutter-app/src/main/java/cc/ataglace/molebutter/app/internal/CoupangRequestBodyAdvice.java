package cc.ataglace.molebutter.app.internal;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

/**
 * Reject retired multi-market payloads instead of silently discarding the
 * user's edits.
 */
@ControllerAdvice
final class CoupangRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private static final MediaType STRUCTURED_JSON = new MediaType("application", "*+json");
    private final ObjectMapper json;
    CoupangRequestBodyAdvice(ObjectMapper json) {
        this.json = json;
    }
    @Override
    public boolean supports(MethodParameter parameter, Type target,
            Class<? extends HttpMessageConverter<?>> converter) {
        String name = parameter.getContainingClass().getSimpleName();
        return name.startsWith("Coupang");
    }
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type target,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        MediaType contentType = input.getHeaders().getContentType();
        if (contentType == null || !(MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || STRUCTURED_JSON.isCompatibleWith(contentType)))
            return input;
        byte[] bytes = input.getBody().readNBytes(11 * 1024 * 1024 + 1);
        if (bytes.length > 11 * 1024 * 1024)
            throw new InputValidationFailure("작업본 요청 크기를 줄여 주세요.");
        if (bytes.length > 0) {
            try {
                check(json.readTree(bytes));
            } catch (tools.jackson.core.JacksonException malformed) {
                throw new org.springframework.http.converter.HttpMessageNotReadableException("Invalid Coupang JSON",
                        malformed, input);
            }
        }
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }
    static void check(JsonNode node) {
        retiredFields(node);
        var document = node.path("document");
        retiredFields(document);
        for (String field : java.util.List.of("basic", "delivery", "settings"))
            retiredFields(document.path(field));
        for (var option : document.path("options")) {
            retiredFields(option);
            retiredFields(option.path("registration"));
        }
    }
    private static void retiredFields(JsonNode node) {
        if (!node.isObject())
            return;
        for (String name : java.util.Set.of("selectedMarkets", "markets", "targets", "market", "originMarket",
                "importMarket", "channelProductId", "common", "overrides", "configuration", "reference", "draftId"))
            if (node.has(name))
                throw new InputValidationFailure("이전 판매 입력 형식은 지원하지 않습니다. 쿠팡 작업본 편집 화면을 새로 열어 주세요.");
    }
}
