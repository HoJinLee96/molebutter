package cc.ataglace.molebutter.procurement.internal;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import cc.ataglace.molebutter.procurement.api.ProductDtos.RefreshResult;

class RefreshResultCompatibilityTest {
    private final ObjectMapper json=JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    @ParameterizedTest @ValueSource(strings={"",",\"lookupPolicy\":null",",\"lookupPolicy\":\"SEARCH_QUERY\""})
    void oldSelectionSnapshotsDefaultToLegacyWithoutRewriting(String policy){
        var basis=json.readValue("{\"supplierId\":\"1\",\"listingKey\":\"LFMALL:p:n\",\"selectedAt\":null,\"changeId\":null"+policy+"}",cc.ataglace.molebutter.procurement.api.SupplierDtos.SelectionBasis.class);
        assertThat(basis.usesSearchQuery()).isEqualTo(policy.contains("SEARCH_QUERY"));assertThat(basis.supplierId()).isEqualTo("1");
    }
    @ParameterizedTest
    @ValueSource(strings={"",",\"recommendationLimited\":null",",\"recommendationLimited\":false",",\"recommendationLimited\":true"})
    void previousAndCurrentResultsKeepTheirValues(String flag){
        var old="""
            {"status":"SUCCESS","searchPrice":94000,"searchMall":"LF몰","searchDeliveryFee":3000,
             "suppliers":[],"checkedAt":"2026-09-27T19:00:00","message":"기존 결과"%s}
            """.formatted(flag);
        var result=json.readValue(old,RefreshResult.class);
        assertThat(result.recommendationLimited()).isEqualTo(flag.endsWith("true"));
        assertThat(result.searchPrice()).isEqualTo(94000L);
        assertThat(result.searchDeliveryFee()).isEqualTo(3000L);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.checkedAt()).isEqualTo(LocalDateTime.parse("2026-09-27T19:00:00"));
        assertThat(result.message()).isEqualTo("기존 결과");
        assertThat(json.readTree(json.writeValueAsString(result)).path("recommendationLimited").isBoolean()).isTrue();
        assertThat(json.readValue(json.writeValueAsString(result),RefreshResult.class)).isEqualTo(result);
    }
    @ParameterizedTest
    @ValueSource(strings={"LFMALL","HAZZYS","NAVER_SMART_STORE","LOTTE_ON","LOTTE_IMALL","HI_THEHYUNDAI","HMALL"})
    void procurementTypeKeepsLegacySnapshotValuesAndMapKeys(String mallCode){
        String snapshot="""
            {"revision":3,
             "stores":[{"id":"1","mall":"%s","kind":"SELLER","name":"기존 매장","identityKey":"legacy","aliases":[],"revision":2}],
             "rules":[{"id":"2","mall":"%s","storeId":"1","revision":4}],
             "branchRequirements":{"%s":false}}
            """.formatted(mallCode,mallCode,mallCode);
        var preferences=json.readValue(snapshot,cc.ataglace.molebutter.procurement.api.SupplierDtos.Preferences.class);
        var mall=cc.ataglace.molebutter.procurement.api.ProductDtos.ProcurementMall.valueOf(mallCode);
        assertThat(preferences.stores().getFirst().mall()).isEqualTo(mall);
        assertThat(preferences.rules().getFirst().mall()).isEqualTo(mall);
        assertThat(preferences.branchRequired(mall)).isFalse();
        assertThat(preferences.allowed(mall,"1")).isTrue();
        String encoded=json.writeValueAsString(preferences);var tree=json.readTree(encoded);
        assertThat(tree.path("stores").get(0).path("mall").asText()).isEqualTo(mallCode);
        assertThat(tree.path("rules").get(0).path("mall").asText()).isEqualTo(mallCode);
        assertThat(tree.path("branchRequirements").has(mallCode)).isTrue();
        assertThat(tree.path("branchRequirements").path(mallCode).asBoolean()).isFalse();
        assertThat(json.readValue(encoded,cc.ataglace.molebutter.procurement.api.SupplierDtos.Preferences.class)).isEqualTo(preferences);
    }
}
