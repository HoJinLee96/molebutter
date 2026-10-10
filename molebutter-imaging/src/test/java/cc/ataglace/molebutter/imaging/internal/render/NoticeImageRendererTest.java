package cc.ataglace.molebutter.imaging.internal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.api.NoticeImageCardDto;
import cc.ataglace.molebutter.imaging.api.NoticeImageLayoutDto;

class NoticeImageRendererTest {

    private final NoticeImageRenderer renderer = new NoticeImageRenderer(
            new FontResolver("Apple SD Gothic Neo", "Nanum Gothic", "Avenir Next", "AppleGothic"));

    @Test
    void excludesProductCodeAndAsResponsibilityWithoutChangingSourceFields() throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("상품코드", "HIHO6F861W2");
        fields.put("상 품-코드", "EXCLUDED-SPACES");
        fields.put(java.text.Normalizer.normalize("상품코드", java.text.Normalizer.Form.NFD), "EXCLUDED-NFD");
        fields.put("종류", "여성 가방");
        fields.put("A/S 책임자와 전화번호", "LF 고객센터 1544-5114");
        fields.put("색상", "블랙");

        assertThat(NoticeImageRenderer.noticeImageFields(fields))
                .containsExactly(entry("종류", "여성 가방"), entry("색상", "블랙"));
        assertThat(renderer.layout(fields).cards()).extracting(NoticeImageCardDto::label)
                .containsExactly("종류", "색상");
        assertThat(renderer.renderPng(fields)).isEqualTo(renderer.renderPng(NoticeImageRenderer.noticeImageFields(fields)));
        assertThat(fields).containsEntry("상품코드", "HIHO6F861W2").hasSize(6);
    }

    @Test
    void publicLayoutKeepsMeasuredCardOrderWidthsAndOriginalText() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("종류", "여성 가방");
        fields.put("색상", "블랙");
        String originalLabel = "취급\n시 주의사항";
        String originalValue = "  젖은 천으로 닦은 뒤\n통풍이 잘 되는 곳에 보관해 주십시오.  ".repeat(12);
        fields.put(originalLabel, originalValue);
        fields.put("제조국", "대한민국");

        List<NoticeImageRenderer.NoticeCard> measured = renderer.layoutCards(fields);
        NoticeImageLayoutDto layout = renderer.layout(fields);

        assertThat(layout.cards()).containsExactly(
                new NoticeImageCardDto("종류", "여성 가방", false),
                new NoticeImageCardDto("색상", "블랙", false),
                new NoticeImageCardDto(originalLabel, originalValue, true),
                new NoticeImageCardDto("제조국", "대한민국", true));
        for (int index = 0; index < measured.size(); index++) {
            NoticeImageRenderer.NoticeCard card = measured.get(index);
            NoticeImageCardDto exposed = layout.cards().get(index);
            assertThat(exposed.label()).isEqualTo(card.label());
            assertThat(exposed.value()).isEqualTo(card.value());
            assertThat(exposed.fullWidth()).isEqualTo(card.width() == 1248);
        }
        assertThat(layout.cards().get(2).value()).contains("\n").startsWith("  ").endsWith("  ");
    }

    @Test
    void publicLayoutExcludesAsAndBlankValuesWithoutChangingSupplierInsertionOrder() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("종류", "가방");
        fields.put("A/S 책임자와 전화번호", "고객센터 1544-5114");
        fields.put("색상", " \n\t ");
        fields.put("제조국", null);
        fields.put("소재", "가죽");

        assertThat(renderer.layout(fields).cards()).containsExactly(
                new NoticeImageCardDto("종류", "가방", false),
                new NoticeImageCardDto("소재", "가죽", false));
    }

    @Test
    void publicLayoutUsesMeasuredNonLatinTextToExpandCards() {
        Map<String, String> fields = new LinkedHashMap<>();
        String longValue = "가나다라마바사".repeat(20);
        fields.put("색상", "블랙");
        fields.put("주의사항", longValue);

        assertThat(renderer.layout(fields).cards()).containsExactly(
                new NoticeImageCardDto("색상", "블랙", true),
                new NoticeImageCardDto("주의사항", longValue, true));
        assertThat(renderer.layoutCards(fields)).extracting(NoticeImageRenderer.NoticeCard::width).containsOnly(1248);
    }

    @Test
    void publicLayoutReturnsTheRenderedPlaceholderWhenNoFieldsRemain() {
        NoticeImageLayoutDto expected = new NoticeImageLayoutDto(List.of(
                new NoticeImageCardDto("정보", "표시할 정보가 없습니다.", true)));

        assertThat(renderer.layout(Map.of())).isEqualTo(expected);
        assertThat(renderer.layout(null)).isEqualTo(expected);
        assertThat(renderer.layout(Map.of("색상", "  ", "A/S 책임자", "고객센터"))).isEqualTo(expected);
    }

    @Test
    void rendersNoticeCardsAtFixedWidth() throws IOException {
        byte[] png = renderer.renderPng(Map.of(
                "상품코드", "DCBA6E370BK",
                "색상", "블랙",
                "취급시 주의사항", "1.제품의 기본 용도 이외에는 사용하지 마십시오. ".repeat(6)));

        // 저장본은 780px 폭, 세로는 작업 캔버스(1440 폭)와 같은 비율로 줄어든다.
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(780);
        assertThat(image.getHeight()).isGreaterThanOrEqualTo(RenderSupport.outputHeight(1440, 420));
        // 배율 반올림으로 마지막 행·열에 검은 부분 픽셀이 남으면 안 된다.
        assertThat(image.getRGB(0, image.getHeight() - 1, 780, 1, null, 0, 780)).containsOnly(Color.WHITE.getRGB());
        int[] lastColumn = image.getRGB(779, 0, 1, image.getHeight(), null, 0, 1);
        assertThat(lastColumn).containsOnly(Color.WHITE.getRGB());
    }

    @Test
    void rendersPlaceholderRowForEmptyFields() throws IOException {
        byte[] png = renderer.renderPng(Map.of());

        BufferedImage saved = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(saved.getWidth()).isEqualTo(780);
        BufferedImage image = renderer.render(Map.of());
        assertThat(image.getWidth()).isEqualTo(1440);
        assertThat(saved.getHeight()).isEqualTo(RenderSupport.outputHeight(image.getWidth(), image.getHeight()));
        assertThat(pixels(renderer.render(null))).isEqualTo(pixels(image));
        assertThat(pixels(renderer.render(Map.of("색상", "  ")))).isEqualTo(pixels(image));
        assertThat(pixels(renderer.render(Map.of("A/S 책임자", "고객센터")))).isEqualTo(pixels(image));
        assertThat(pixels(renderer.render(Map.of("상품코드", "BAG1")))).isEqualTo(pixels(image));
        assertThat(saved.getHeight()).isLessThan(RenderSupport.outputHeight(1440, 520));
    }

    @Test
    void usesNeutralCardsWithWhiteGuttersInsteadOfTableRules() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("색상", "블랙");
        fields.put("종류", "가방");
        BufferedImage image = renderer.render(fields);
        List<NoticeImageRenderer.NoticeCard> cards = renderer.layoutCards(fields);

        boolean grayscale = true;
        for (int rgb : pixels(image)) {
            if (((rgb >> 16) & 255) != ((rgb >> 8) & 255) || ((rgb >> 8) & 255) != (rgb & 255)) {
                grayscale = false;
                break;
            }
        }
        assertThat(grayscale).isTrue();
        for (NoticeImageRenderer.NoticeCard card : cards) {
            assertThat(image.getRGB(card.x() + 10, card.y() + 10))
                    .isEqualTo(new Color(246, 246, 246).getRGB());
        }
        int gutterX = cards.getFirst().x() + cards.getFirst().width() + 14;
        assertThat(image.getRGB(gutterX, cards.getFirst().y(), 1, cards.getFirst().height(), null, 0, 1))
                .containsOnly(Color.WHITE.getRGB());
        assertWhiteMargins(image);
    }

    @Test
    void usesTheFormerTitleColumnForCardsWithSymmetricOuterMargins() {
        BufferedImage image = renderer.render(Map.of());
        NoticeImageRenderer.NoticeCard card = renderer.layoutCards(Map.of()).getFirst();
        assertThat(card.x()).isEqualTo(96);
        assertThat(card.width()).isEqualTo(1248);
        assertThat(card.y()).isEqualTo(72);
        assertThat(image.getRGB(96, 72)).isEqualTo(new Color(246, 246, 246).getRGB());
        assertThat(image.getRGB(0, 0, 1440, 72, null, 0, 1440)).containsOnly(Color.WHITE.getRGB());
        assertWhiteMargins(image);
    }

    @Test
    void placesKindBesideManufacturerInTheSecondRowWithoutChangingSourceFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("제품 소재(제품 주소재)", "라미네이팅가죽(소가죽)");
        fields.put("색상", "블랙");
        fields.put("제조사(공식수입/병행수입)", "LF");
        fields.put("제조국", "중국 * 제조국 정보는 최초 생산지 기준이며, 추가 생산이 이루어질 경우 제조국이 달라질 수 있습니다. ".repeat(2));
        fields.put("취급시 주의사항", "제품의 기본 용도 이외에는 사용하지 마십시오. ".repeat(5));
        fields.put("종류", "크로스백");

        List<String> originalOrder = List.copyOf(fields.keySet());
        List<NoticeImageRenderer.NoticeCard> cards = renderer.layoutCards(fields);

        assertThat(cards).extracting(NoticeImageRenderer.NoticeCard::label).containsExactly(
                "제품 소재(제품 주소재)", "색상", "제조사(공식수입/병행수입)", "종류", "제조국", "취급시 주의사항");
        assertThat(cards.get(0).y()).isEqualTo(cards.get(1).y());
        assertThat(cards.get(2).y()).isEqualTo(cards.get(3).y()).isGreaterThan(cards.get(0).y());
        assertThat(cards.get(2).width()).isEqualTo(615);
        assertThat(cards.get(3).x()).isEqualTo(cards.get(2).x() + cards.get(2).width() + 18);
        assertThat(renderer.layout(fields).cards().subList(2, 4)).containsExactly(
                new NoticeImageCardDto("제조사(공식수입/병행수입)", "LF", false),
                new NoticeImageCardDto("종류", "크로스백", false));
        assertThat(fields.keySet()).containsExactlyElementsOf(originalOrder);
        assertWhiteMargins(renderer.render(fields));
    }

    @Test
    void keepsManufacturerAndKindTogetherAfterAnOddFieldAndWrapsLongValues() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("색상", "블랙");
        fields.put("종 류", "크로스백");
        fields.put(java.text.Normalizer.normalize("제조사 (공식수입/병행수입)", java.text.Normalizer.Form.NFD),
                "긴 제조사명과 수입자 정보 ".repeat(10));
        List<NoticeImageRenderer.NoticeCard> cards = renderer.layoutCards(fields);

        assertThat(cards.get(0).width()).isEqualTo(1248);
        assertThat(cards.get(1).y()).isEqualTo(cards.get(2).y());
        assertThat(cards.get(1).height()).isEqualTo(cards.get(2).height()).isGreaterThan(144);
        assertThat(cards.get(2).label()).isEqualTo("종 류");
        assertThat(String.join("", cards.get(1).valueLines()).replace(" ", ""))
                .isEqualTo(fields.values().stream().toList().get(2).replace(" ", ""));
        assertWhiteMargins(renderer.render(fields));
    }

    @Test
    void keepsRemainingFieldOrderWhenManufacturerOrKindHasNoValue() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("종류", "크로스백");
        fields.put("색상", "블랙");
        fields.put("제조사", " ");
        assertThat(renderer.layout(fields).cards()).extracting(NoticeImageCardDto::label)
                .containsExactly("종류", "색상");
        fields.put("제조사", "LF");
        fields.put("종류", " ");
        assertThat(renderer.layout(fields).cards()).extracting(NoticeImageCardDto::label)
                .containsExactly("색상", "제조사");
    }

    @Test
    void growsCardsForLongValuesWithoutClippingMarginsOrTheLastCard() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("취급 시 주의사항", "직사광선을 피하고 통풍이 잘 되는 곳에 보관해 주십시오. ".repeat(30));
        fields.put("색상", "블랙");
        BufferedImage image = renderer.render(fields);

        assertThat(image.getHeight()).isGreaterThan(renderer.render(Map.of("색상", "블랙")).getHeight());
        assertWhiteMargins(image);
        NoticeImageRenderer.NoticeCard last = renderer.layoutCards(fields).getLast();
        NoticeImageRenderer.NoticeCard only = renderer.layoutCards(Map.of("색상", "블랙")).getFirst();
        BufferedImage lastCardOnly = renderer.render(Map.of("색상", "블랙"));
        assertThat(image.getRGB(last.x(), last.y(), last.width(), last.height(), null, 0, last.width()))
                .isEqualTo(lastCardOnly.getRGB(only.x(), only.y(), only.width(), only.height(), null, 0, only.width()));
    }

    @Test
    void growsCardsForLongUnbrokenLabelsAndValues() {
        BufferedImage image = renderer.render(Map.of("매우긴상품정보항목".repeat(16), "가나다라마바사".repeat(40)));

        assertThat(image.getHeight()).isGreaterThan(520);
        assertWhiteMargins(image);
    }

    @Test
    void pairsShortFieldsInReadingOrderAndExpandsTheOddLastCard() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("종류", "가방");
        fields.put("색상", "블랙");
        fields.put("제조국", "대한민국");
        List<NoticeImageRenderer.NoticeCard> cards = renderer.layoutCards(fields);

        assertThat(cards).extracting(card -> String.join("", card.labelLines()))
                .containsExactly("종류", "색상", "제조국");
        assertThat(cards.get(0).y()).isEqualTo(cards.get(1).y());
        assertThat(cards.get(0).height()).isEqualTo(cards.get(1).height());
        assertThat(cards.get(1).x()).isGreaterThan(cards.get(0).x() + cards.get(0).width());
        assertThat(cards.get(2).y()).isGreaterThan(cards.get(0).y() + cards.get(0).height());
        assertThat(cards.get(2).width()).isEqualTo(1248);
    }

    @Test
    void expandsTheUnpairedShortFieldBeforeALongFieldWithoutReordering() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("색상", "블랙");
        fields.put("주의사항", "통풍이 잘 되는 곳에 보관해 주십시오. ".repeat(10));
        fields.put("제조국", "대한민국");
        List<NoticeImageRenderer.NoticeCard> cards = renderer.layoutCards(fields);

        assertThat(cards).extracting(NoticeImageRenderer.NoticeCard::width).containsOnly(1248);
        assertThat(cards).extracting(card -> String.join("", card.labelLines()))
                .containsExactly("색상", "주의사항", "제조국");
        assertThat(cards.get(1).height()).isGreaterThan(cards.get(0).height());
    }

    @Test
    void drawsEachValueBelowItsLabelWithVisibleSpace() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("색상", "블랙");
        fields.put("종류", "가방");
        BufferedImage image = renderer.render(fields);
        for (NoticeImageRenderer.NoticeCard card : renderer.layoutCards(fields)) {
            int labelBottom = -1;
            int valueTop = image.getHeight();
            for (int y = card.y(); y < card.y() + card.height(); y++) {
                for (int x = card.x(); x < card.x() + card.width(); x++) {
                    int rgb = image.getRGB(x, y) & 0xFFFFFF;
                    // Value antialiasing can also contain gray pixels; keep the label region separate.
                    if (rgb == 0x5A5A5A && y < card.y() + 60) labelBottom = Math.max(labelBottom, y);
                    if (rgb == 0x222222) valueTop = Math.min(valueTop, y);
                }
            }
            assertThat(labelBottom).isGreaterThan(card.y());
            assertThat(valueTop).isLessThan(card.y() + card.height());
            assertThat(valueTop - labelBottom).isGreaterThanOrEqualTo(12);
        }
    }

    @Test
    void mixedLengthCardsNeverOverlapAndKeepAllText() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("소재", "가죽");
        fields.put("색상", "블랙");
        fields.put("긴항목".repeat(10), "긴내용".repeat(80));
        fields.put("제조국", "대한민국");
        fields.put("기준", "상품 상세 설명 참고");
        List<NoticeImageRenderer.NoticeCard> cards = renderer.layoutCards(fields);

        assertThat(cards).hasSize(fields.size());
        assertThat(cards).extracting(card -> String.join("", card.valueLines()).replace(" ", ""))
                .containsExactlyElementsOf(fields.values().stream().map(value -> value.replace(" ", "")).toList());
        for (int i = 0; i < cards.size(); i++) {
            NoticeImageRenderer.NoticeCard first = cards.get(i);
            for (int j = i + 1; j < cards.size(); j++) {
                NoticeImageRenderer.NoticeCard second = cards.get(j);
                assertThat(first.x() + first.width() <= second.x()
                        || second.x() + second.width() <= first.x()
                        || first.y() + first.height() <= second.y()
                        || second.y() + second.height() <= first.y()).isTrue();
            }
        }
        assertWhiteMargins(renderer.render(fields));
    }

    @Test
    void noticeFontCanBeConfiguredWithoutChangingSizeGuideFonts() {
        FontResolver fonts = new FontResolver("Serif", "SansSerif", "Dialog", "Monospaced");

        assertThat(fonts.noticeFont(Font.PLAIN, 30).getFamily()).isEqualTo("Monospaced");
        assertThat(fonts.font(Font.PLAIN, 30).getFamily()).isEqualTo("Serif");
        assertThat(fonts.photoTextFont(Font.PLAIN, 30).getFamily()).isEqualTo("SansSerif");
        assertThat(fonts.numberFont(Font.PLAIN, 30).getFamily()).isEqualTo("Dialog");
    }

    @Test
    void rejectsUnboundedNoticeFieldsAndTextBeforeLayout() {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < 101; index++) {
            fields.put("항목 " + index, "내용");
        }
        assertInvalidNotice(fields);
        assertInvalidNotice(Map.of("항목".repeat(257), "내용"));
        assertInvalidNotice(Map.of("항목", "x".repeat(8193)));
        fields.clear();
        for (int index = 0; index < 10; index++) {
            fields.put("항목 " + index, "x".repeat(7000));
        }
        assertInvalidNotice(fields);
    }

    @Test
    void rejectsNoticeWhoseCardRowsWouldExceedMaximumImageHeight() {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            fields.put("항목 " + index, "내용");
        }
        assertThatThrownBy(() -> renderer.render(fields))
                .isInstanceOfSatisfying(ImagingFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.INVALID_INPUT))
                .hasMessageContaining("높이");
    }

    private void assertInvalidNotice(Map<String, String> fields) {
        assertThatThrownBy(() -> renderer.render(fields))
                .isInstanceOfSatisfying(ImagingFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.INVALID_INPUT));
    }

    private void assertWhiteMargins(BufferedImage image) {
        assertThat(image.getRGB(0, 0, 96, image.getHeight(), null, 0, 96))
                .containsOnly(Color.WHITE.getRGB());
        assertThat(image.getRGB(1344, 0, 96, image.getHeight(), null, 0, 96))
                .containsOnly(Color.WHITE.getRGB());
        assertThat(image.getRGB(0, image.getHeight() - 60, 1440, 60, null, 0, 1440))
                .containsOnly(Color.WHITE.getRGB());
    }

    private int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
