package cc.ataglace.molebutter.coupang.internal.client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway.*;
import static org.assertj.core.api.Assertions.*;

class CoupangWriteOutcomeTest {
    final ObjectMapper json = new ObjectMapper();
    final CoupangWriteOutcome parser = new CoupangWriteOutcome(json);
    final Mapping mapping = new Mapping("account", null, List.of());
    final Step step = new Step("create", Type.CREATE, null, "POST", "/synthetic", "",
            "{\"sellerProductName\":\"합성비공개상품\",\"vendorUserId\":\"private-seller\",\"companyContactNumber\":\"010-1234-5678\"}", "{}", "{}");
    byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    @Test void rejectedCreationRetainsOnlyRedactedDiagnosticsInBothEnvelopeFormats() {
        String response = """
            {"code":"ERROR","message":"필수 구매 옵션 누락: 합성비공개상품",
            "details":"카테고리 규격을 확인하세요. https://private.example.test/file?token=hidden",
            "errorItems":[{"itemIndex":0,"itemName":"private-seller","itemAttributes":[
              {"attributeTypeName":"색상","attributeValueName":"private-seller","message":"필수 구매 옵션을 입력해주세요."}]}],
            "unrecognized":{"message":"NEVER_COPY_THIS"}}
            """;
        for (String body : List.of(response, "{\"code\":\"200\",\"data\":" + response + "}")) {
            var result = parser.parseOutcome(bytes(body), step, mapping, Instant.now());
            assertThat(result.state()).isEqualTo(State.FAILED);
            assertThat(result.code()).isEqualTo("HTTP_200_REJECTED");
            assertThat(result.mapping().sellerProductId()).isNull();
            assertThat(result.rejectionDetails()).contains("필수 구매 옵션 누락", "옵션 1 · 색상", "카테고리 규격")
                    .doesNotContain("합성비공개상품", "private-seller", "private.example", "hidden", "NEVER_COPY_THIS");
        }
    }
    @Test void knownRejectionKeepsCodeAndDetailsButAmbiguousAndAuthResultsDoNotExposeBodies() {
        var body = bytes("{\"code\":\"ERROR\",\"message\":\"가격은 최소 10원 단위로 입력하세요\"}");
        assertThat(parser.parseOutcome(body, step, mapping, Instant.now()).code()).isEqualTo("PRICE_UNIT");
        var rejected = parser.httpFailure(400, body, mapping, Instant.now(), step.bodyJson());
        assertThat(rejected.state()).isEqualTo(State.FAILED);
        assertThat(rejected.rejectionDetails()).contains("10원");
        for (int status : List.of(401, 403, 429, 500, 302))
            assertThat(parser.httpFailure(status, body, mapping, Instant.now(), step.bodyJson()).rejectionDetails()).isNull();
        assertThat(parser.httpFailure(500, body, mapping, Instant.now()).state()).isEqualTo(State.UNKNOWN);
        assertThat(parser.parseOutcome(bytes("not json"), step, mapping, Instant.now()).state()).isEqualTo(State.UNKNOWN);
        var accepted = parser.parseOutcome(bytes("{\"code\":\"SUCCESS\",\"data\":123}"), step, mapping, Instant.now());
        assertThat(accepted.state()).isEqualTo(State.ACCEPTED);
        assertThat(accepted.rejectionDetails()).isNull();
    }
    @Test void oldPersistedResultStillDeserializesWithoutDetails() {
        var result = json.readValue("{\"state\":\"UNKNOWN\",\"code\":\"INTERRUPTED\"}", Result.class);
        assertThat(result.state()).isEqualTo(State.UNKNOWN);
        assertThat(result.rejectionDetails()).isNull();
    }
}
