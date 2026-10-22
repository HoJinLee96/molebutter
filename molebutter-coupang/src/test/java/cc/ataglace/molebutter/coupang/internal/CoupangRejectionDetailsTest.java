package cc.ataglace.molebutter.coupang.internal;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class CoupangRejectionDetailsTest {
    final ObjectMapper json = new ObjectMapper();
    @Test void secretsAndPersonalLabelsSuppressEntireFragmentEvenWithUnexpectedFormatting() {
        for (String text : List.of("Authorization: CEA algorithm=x, access-key=SYNTHETIC_KEY", "Cookie: session=synthetic",
                "secret = some short value", "토큰 token 은 SYNTHETIC", "연락처 01012345678", "주소 서울시 합성구 합성로 12",
                "vendorUserId=private-person", "성명 김합성", "password: a b c", "Ａｕｔｈｏｒｉｚａｔｉｏｎ: test")) {
            String clean = CoupangRejectionDetails.clean(text);
            assertThat(clean).isEqualTo("[개인정보·인증정보가 포함된 설명 생략]");
        }
    }
    @Test void unlabelledValuesUrlsMailNumbersAndOpaqueCredentialsAreRemoved() {
        var body = json.createObjectNode().put("message", "합성판매자, 010-1234-5678, seller@example.test, "
                + "https://example.test/private.png?x=y, 900008039797778533, abcdefghijklmnopqrstuvwxyz012345");
        String result = CoupangRejectionDetails.extract(json, body, "{\"sellerProductName\":\"합성판매자\"}");
        assertThat(result).contains("[비공개]").doesNotContain("합성판매자", "010", "example", "900008", "abcdefgh");
    }
    @Test void boundsRunAfterRedactionAndUnsupportedFieldsNeverEscape() {
        var body = json.createObjectNode().put("message", "이미지 형식을 확인하세요. ".repeat(300))
                .put("details", "x".repeat(8193)).put("itemName", "PRIVATE");
        String result = CoupangRejectionDetails.extract(json, body, "{}");
        assertThat(result.length()).isLessThanOrEqualTo(CoupangRejectionDetails.MAX_LENGTH);
        assertThat(result).doesNotContain("PRIVATE");
        assertThat(CoupangRejectionDetails.extract(json, body, "invalid JSON")).isNull();
        assertThat(CoupangRejectionDetails.extract(json, json.readTree("{\"details\":{\"private\":\"secret\"}}"), "{}")).isNull();
    }
    @Test void persistedMessagesKeepGuidanceAndBoundedDetailsOnlyForRejectedFailures() {
        String message = SubmissionStore.publicMessage("FAILED", "HTTP_200_REJECTED", "필수 구매 옵션을 입력해주세요.\n옵션 1 · 색상: 값이 없습니다.");
        assertThat(message).contains("HTTP 200", "쿠팡 상세 사유:", "옵션 1 · 색상");
        assertThat(SubmissionStore.publicMessage("FAILED", "PRICE_UNIT", "설명 ".repeat(200))).hasSizeLessThanOrEqualTo(500);
        for (String state : List.of("UNKNOWN", "ACCEPTED", "SUCCEEDED"))
            assertThat(SubmissionStore.publicMessage(state, "HTTP_200_REJECTED", "숨길 사유")).doesNotContain("숨길 사유");
        assertThat(SubmissionStore.publicMessage("FAILED", "MAPPING_CHANGED", "숨길 사유")).doesNotContain("숨길 사유");
    }
    @Test void actionableOptionErrorsComeBeforeLongGeneralDetails() {
        var body = json.createObjectNode().put("message", "구매 옵션 누락").put("details", "참고 설명 ".repeat(100));
        body.putArray("errorItems").addObject().put("itemIndex", 1).putArray("itemAttributes").addObject()
                .put("attributeTypeName", "총 수량").put("message", "필수 구매 옵션이 없습니다.");
        assertThat(CoupangRejectionDetails.extract(json, body, "{}"))
                .contains("옵션 2 · 총 수량: 필수 구매 옵션이 없습니다.");
    }
    @Test void numericPersonalValuesAreRemovedEvenNextToKoreanCharacters() {
        var body = json.createObjectNode().put("message", "입력01012345678의 값과 12345를 확인하세요.");
        assertThat(CoupangRejectionDetails.extract(json, body, "{\"companyContactNumber\":\"01012345678\",\"returnZipCode\":12345}"))
                .doesNotContain("01012345678", "12345").contains("확인하세요");
    }
}
