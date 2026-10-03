package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.service.product.ProductBrandMatcher;

class ProductBrandMatcherTest {
    private final ProductBrandMatcher matcher = new ProductBrandMatcher(Map.of(
        1L,"헤지스",2L,"DAKS",3L,"폴로 랄프로렌",4L,"A.P.C."));

    @Test void matchesRegisteredNamesWithBracketsWhitespaceAndCaseNormalization() {
        assertThat(matcher.match("[헤지스] 여성 가방 HIBA311")).isEqualTo(1L);
        assertThat(matcher.match("［ｄａｋｓ］ 체크 셔츠")).isEqualTo(2L);
        assertThat(matcher.match("폴로\u00a0  랄프로렌 셔츠")).isEqualTo(3L);
        assertThat(matcher.match("[A.P.C.] 가방")).isEqualTo(4L);
        assertThat(matcher.match("헤지스 / 헤지스 가방")).isEqualTo(1L);
    }

    @Test void leavesUnregisteredAmbiguousAndPartialWordsUnassigned() {
        for(String title:List.of("헤지스/DAKS 가방","헤지스풍 가방","NOTDAKS 셔츠","헤지스ACC 가방","닥스 셔츠","신규브랜드 셔츠",""))
            assertThat(matcher.match(title)).as(title).isNull();
        assertThat(matcher.match(null)).isNull();
        assertThat(new ProductBrandMatcher(Map.of(1L,"DAKS",2L,"daks")).match("DAKS 셔츠")).isNull();
    }

    @Test void requiresConsistentEvidenceAcrossAllLinkedSalesTitles() {
        assertThat(matcher.matchAll(List.of("헤지스 가방 블랙","[헤지스] 가방 베이지"))).isEqualTo(1L);
        assertThat(matcher.matchAll(List.of("헤지스 가방","DAKS 가방"))).isNull();
        assertThat(matcher.matchAll(List.of("헤지스 가방","미확인 가방"))).isNull();
        assertThat(matcher.matchAll(List.of())).isNull();
    }
}
