package cc.ataglace.molebutter.imaging.internal.parse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

import tools.jackson.databind.JsonNode;

/** LF몰 비공식 API 응답(JSON/고시 HTML)에서 필요한 값을 뽑는 순수 파서. */
public final class LfmallResponseParser {

    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "<tr[^>]*>\\s*<th[^>]*>(.*?)</th>\\s*<td[^>]*>(.*?)</t[hd]>\\s*</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private LfmallResponseParser() {
    }

    public static String productName(JsonNode init) {
        return text(init.path("body").path("productBasicDTO"), "productName");
    }

    public static Integer originalPrice(JsonNode price) {
        return integer(price.path("body").path("productPriceDTO"), "originalPrice");
    }

    public static Integer salePrice(JsonNode price) {
        return integer(price.path("body").path("productPriceDTO"), "salePrice");
    }

    /** 고시 HTML(<tr><th>라벨</th><td>값</td></tr> 표)을 순서 보존 Map 으로 파싱. */
    public static Map<String, String> parseNotificationFields(JsonNode notifications) {
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode list = notifications.path("body").path("productNotificationDTOList");
        if (!list.isArray()) {
            return fields;
        }
        for (JsonNode item : list) {
            String html = item.path("value").asText("");
            Matcher matcher = TABLE_ROW_PATTERN.matcher(html);
            while (matcher.find()) {
                String key = cleanHtmlCell(matcher.group(1));
                String value = cleanHtmlCell(matcher.group(2));
                if (!key.isBlank()) {
                    fields.put(key, value);
                }
            }
        }
        return fields;
    }

    static String cleanHtmlCell(String html) {
        return HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", " "))
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** displaySequence 오름차순 정렬 후 imageUrl 추출(distinct). */
    public static List<String> extractImageUrls(JsonNode contents) {
        List<JsonNode> imageNodes = new ArrayList<>();
        JsonNode images = contents.path("body").path("productContentsDTO").path("productImageDTOList");
        if (images.isArray()) {
            images.forEach(imageNodes::add);
        }
        return imageNodes.stream()
                .sorted(Comparator.comparingInt(node -> node.path("displaySequence").asInt(999)))
                .map(node -> text(node, "imageUrl"))
                .filter(url -> !url.isBlank())
                .distinct()
                .toList();
    }

    public static List<String> extractCategoryNames(JsonNode categories) {
        List<String> names = new ArrayList<>();
        appendCategoryNames(names, categories.path("body").path("productStandardCategoriesDTOList"),
                "standardCategoryName");
        appendCategoryNames(names, categories.path("body").path("productDetailCategoriesDTOList"),
                "productDetailCategoryName");
        return names.stream()
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
    }

    private static void appendCategoryNames(List<String> names, JsonNode categoryList, String fieldName) {
        if (!categoryList.isArray()) {
            return;
        }
        for (JsonNode category : categoryList) {
            String categoryName = text(category, fieldName);
            if (!categoryName.isBlank()) {
                names.add(categoryName);
            }
        }
    }

    /** 사이즈차트에서 섹션명 → 실측값 Map 추출. */
    public static Map<String, String> extractSizeMeasurements(JsonNode sizeChart) {
        Map<String, String> measurements = new LinkedHashMap<>();
        JsonNode chart = sizeChart.path("body").path("sizeChart").path("productSizeChartMDPResultVOList");
        if (!chart.isArray() || chart.isEmpty()) {
            return measurements;
        }

        Map<String, String> sectionNames = new LinkedHashMap<>();
        JsonNode sections = chart.get(0).path("sectionList");
        if (sections.isArray()) {
            for (JsonNode section : sections) {
                sectionNames.put(text(section, "sectionCd"), text(section, "sectionName").trim());
            }
        }

        JsonNode sizeInfos = chart.get(0).path("productSizeMdpInfoResultList");
        if (!sizeInfos.isArray() || sizeInfos.isEmpty()) {
            return measurements;
        }

        JsonNode details = sizeInfos.get(0).path("productSizeMDPInfoDtlResultVOList");
        if (details.isArray()) {
            for (JsonNode detail : details) {
                String sectionCd = text(detail, "sectionCd");
                String sectionName = sectionNames.getOrDefault(sectionCd, sectionCd);
                String sectionValue = text(detail, "sectionValue");
                if (!sectionName.isBlank() && !sectionValue.isBlank()) {
                    measurements.put(sectionName, sectionValue);
                }
            }
        }
        return measurements;
    }

    /** 옵션 응답에서 sizeValue/sizeCode 노드 목록 반환. */
    public static List<JsonNode> optionSizeNodes(JsonNode options) {
        JsonNode sizes = options.path("body").path("productOptionDTO").path("productOptionSizeDTOList");
        if (!sizes.isArray()) {
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>();
        sizes.forEach(nodes::add);
        return nodes;
    }

    public static String text(JsonNode node, String fieldName) {
        return node.path(fieldName).asText("");
    }

    public static Integer integer(JsonNode node, String fieldName) {
        return node.path(fieldName).isNumber() ? node.path(fieldName).asInt() : null;
    }
}
