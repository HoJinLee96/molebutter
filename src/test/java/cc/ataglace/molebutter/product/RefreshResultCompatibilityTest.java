package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import cc.ataglace.molebutter.dto.product.ProductDtos.RefreshResult;

class RefreshResultCompatibilityTest {
    private final ObjectMapper json=JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    @ParameterizedTest @ValueSource(strings={"",",\"lookupPolicy\":null",",\"lookupPolicy\":\"SEARCH_QUERY\""})
    void oldSelectionSnapshotsDefaultToLegacyWithoutRewriting(String policy){
        var basis=json.readValue("{\"supplierId\":\"1\",\"listingKey\":\"LFMALL:p:n\",\"selectedAt\":null,\"changeId\":null"+policy+"}",cc.ataglace.molebutter.dto.product.SupplierDtos.SelectionBasis.class);
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
}
