package cc.ataglace.molebutter.coupang.internal.client;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.CoupangCatalog.Attribute;
import cc.ataglace.molebutter.coupang.api.CoupangEditor.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
class CoupangPurchaseAttributesTest {
    final ObjectMapper json = new ObjectMapper();
    Attribute a(String name, String value) {
        return new Attribute(name, value, "EXPOSED");
    }
    CategoryRules rules(AttributeRule... a) {
        return new CategoryRules("10", true, List.of(a), List.of(), List.of(), List.of(), List.of());
    }
    AttributeRule rule(String name, String group, String type, String... units) {
        return new AttributeRule(name, "EXPOSED", "MANDATORY", group, type, null, List.of(units));
    }
    @Test
    void permissionsNormalizeOnlyBooleanAndTrueFalseStrings() {
        for (var text : List.of("true", "\"true\"", "\"TRUE\""))
            assertThat(CoupangProductClient.editable(json.readTree(text))).isTrue();
        for (var text : List.of("false", "\"false\""))
            assertThat(CoupangProductClient.editable(json.readTree(text))).isFalse();
        for (var text : List.of("null", "1", "\"yes\"", "{}"))
            assertThat(CoupangProductClient.editable(json.readTree(text))).isNull();
    }
    @Test
    void changesPreserveUnknownFieldsAndUntouchedAttributesAndOnlyPatchValues() {
        var raw = json.readTree(
                "[{\"exposed\":\"EXPOSED\",\"attributeTypeName\":\"색상\",\"attributeValueName\":\"블랙\",\"editable\":\"true\",\"future\":7},{\"exposed\":\"EXPOSED\",\"attributeTypeName\":\"사이즈\",\"attributeValueName\":\"FREE\"}]");
        var result = CoupangPurchaseAttributes.patch(json, raw, List.of(a("색상", "블랙"), a("사이즈", "FREE")),
                List.of(a("색상", "브라운"), a("사이즈", "FREE")), rules(rule("색상", "NONE", "STRING")));
        assertThat(result.get(0).path("attributeValueName").asString()).isEqualTo("브라운");
        assertThat(result.get(0).path("future").asInt()).isEqualTo(7);
        assertThat(result.get(1)).isEqualTo(raw.get(1));
    }
    @Test
    void falseUnknownAndPresentEmptyCannotMasqueradeAsMissing() {
        for (var flag : List.of("false", "null", "\"unknown\"")) {
            var raw = json.readTree(
                    "[{\"exposed\":\"EXPOSED\",\"attributeTypeName\":\"색상\",\"attributeValueName\":\"\",\"editable\":"
                            + flag + "}]");
            assertThatThrownBy(() -> CoupangPurchaseAttributes.patch(json, raw, List.of(a("색상", "")),
                    List.of(a("색상", "브라운")), rules(rule("색상", "NONE", "STRING"))))
                    .isInstanceOf(InputValidationFailure.class).hasMessageContaining("권한");
        }
    }
    @Test
    void addMissingMandatoryButNeverRenameDeleteOrReplaceExistingGroup() {
        var raw = json.readTree("[]");
        var rules = rules(rule("색상", "NONE", "STRING"));
        assertThat(CoupangPurchaseAttributes.patch(json, raw, List.of(), List.of(a("색상", "브라운")), rules)).hasSize(1);
        assertThatThrownBy(() -> CoupangPurchaseAttributes.patch(json, raw, List.of(a("색상", "블랙")), List.of(), rules))
                .hasMessageContaining("삭제");
        assertThatThrownBy(() -> CoupangPurchaseAttributes.patch(json, raw, List.of(a("색상", "블랙")),
                List.of(a("컬러", "브라운")), rules)).hasMessageContaining("이름");
    }
    @Test
    void numericUnitsDateAndGroupChooseOneAreValidated() {
        var weight = rule("중량", "1", "NUMBER", "g", "kg");
        var volume = rule("용량", "1", "NUMBER", "ml");
        CoupangPurchaseAttributes.value(weight, "0g");
        assertThatThrownBy(() -> CoupangPurchaseAttributes.value(weight, "2ml")).hasMessageContaining("단위");
        assertThatThrownBy(() -> CoupangPurchaseAttributes.value(rule("날짜", "NONE", "DATE"), "2026-02-30"))
                .hasMessageContaining("날짜");
        assertThatThrownBy(() -> CoupangPurchaseAttributes.patch(json, json.readTree("[]"), List.of(),
                List.of(a("중량", "1g"), a("용량", "2ml")), rules(weight, volume))).hasMessageContaining("한 항목");
        assertThat(CoupangPurchaseAttributes.patch(json, json.readTree("[]"), List.of(),
                List.of(a("중량", "1g"), a("용량", "")), rules(weight, volume))).hasSize(1);
    }
    @Test
    void optionalGroupsAllowNoneButRejectMultipleAndCreateNamesUseActualValues() {
        var r = rules(new AttributeRule("중량", "EXPOSED", "OPTIONAL", "1", "NUMBER", null, List.of("g")),
                new AttributeRule("용량", "EXPOSED", "OPTIONAL", "1", "NUMBER", null, List.of("ml")));
        CoupangPurchaseAttributes.create(json.readTree("[{\"attributes\":[]}]"), r);
        assertThatThrownBy(() -> CoupangPurchaseAttributes.create(json.readTree(
                "[{\"attributes\":[{\"attributeTypeName\":\"중량\",\"attributeValueName\":\"1g\",\"exposed\":\"EXPOSED\"},{\"attributeTypeName\":\"용량\",\"attributeValueName\":\"2ml\",\"exposed\":\"EXPOSED\"}]}]"),
                r)).hasMessageContaining("한 항목");
        assertThat(CoupangPurchaseAttributes.itemName(json.readTree(
                "[{\"attributeTypeName\":\"사이즈\",\"attributeValueName\":\"FREE\",\"exposed\":\"EXPOSED\"},{\"attributeTypeName\":\"색상\",\"attributeValueName\":\"브라운\",\"exposed\":\"EXPOSED\"}]")))
                .isEqualTo("브라운 FREE");
    }
    @Test
    void untouchedLegacyPurchaseAttributesDoNotRequireCurrentRules() {
        var raw = json.readTree(
                "[{\"exposed\":\"EXPOSED\",\"attributeTypeName\":\"구형\",\"attributeValueName\":\"\",\"editable\":false}]");
        assertThat(CoupangPurchaseAttributes.patch(json, raw, List.of(a("구형", "")), List.of(a("구형", "")), rules()))
                .isEqualTo(raw);
    }
}
