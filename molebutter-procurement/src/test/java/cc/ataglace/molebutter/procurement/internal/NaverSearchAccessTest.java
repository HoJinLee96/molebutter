package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.internal.NaverPriceSearch;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NaverSearchAccessTest {
    private static final String SEARCH_URL="https://search.shopping.naver.com/search/all";

    @Test void distinguishesForbiddenFromRateLimitedResponses() {
        assertThatThrownBy(()->NaverPriceSearch.checkAccess(SEARCH_URL,403,"접근이 제한되었습니다."))
            .isInstanceOf(NaverPriceSearch.SearchBlocked.class).hasMessage("[네이버 검색 제한] HTTP 403. 잠시 후 재개해 주세요.");
        assertThatThrownBy(()->NaverPriceSearch.checkAccess(SEARCH_URL,429,""))
            .isInstanceOf(NaverPriceSearch.SearchBlocked.class).hasMessageContaining("[네이버 검색 제한]","HTTP 429");
    }

    @Test void reportsLoginRequirementSeparately() {
        assertThatThrownBy(()->NaverPriceSearch.checkAccess("https://nid.naver.com/nidlogin.login",200,""))
            .isInstanceOf(NaverPriceSearch.SearchBlocked.class).hasMessageContaining("로그인하지 않습니다");
        assertThatThrownBy(()->NaverPriceSearch.checkAccess(SEARCH_URL,200,"아이디 또는 전화번호\n일회용 번호"))
            .isInstanceOf(NaverPriceSearch.SearchBlocked.class).hasMessageContaining("로그인하지 않습니다");
    }

    @ParameterizedTest @ValueSource(strings={"접근이 제한되었습니다.","비정상적인 접근입니다.","보안 확인","자동입력 방지"})
    void stopsEvenWhenRestrictionPageReturnsHttpOk(String text) {
        assertThatThrownBy(()->NaverPriceSearch.checkAccess(SEARCH_URL,200,text))
            .isInstanceOfSatisfying(NaverPriceSearch.SearchBlocked.class, failure -> {
                assertThat(failure.reason()).isEqualTo(NaverPriceSearch.BlockReason.SECURITY_CHECK);
                assertThat(NaverSearchFailure.temporary(failure.reason().name(),failure.httpStatus())).isTrue();
            });
    }

    @Test void onlyExplicitRetryableRestrictionsJoinTheBackoffPolicy() {
        for(int status:new int[]{418,429})assertThatThrownBy(()->NaverPriceSearch.checkAccess(SEARCH_URL,status,"보안 확인"))
            .isInstanceOfSatisfying(NaverPriceSearch.SearchBlocked.class,failure->{
                assertThat(failure.httpStatus()).isEqualTo(status);
                assertThat(NaverSearchFailure.temporary(failure.reason().name(),failure.httpStatus())).isTrue();
            });
        for(String reason:new String[]{"ACCESS_RESTRICTED","AUTHENTICATED_SESSION","TAB_CLEANUP_FAILED","BROWSER_UNAVAILABLE"})
            assertThat(NaverSearchFailure.temporary(reason,null)).isFalse();
        assertThatThrownBy(()->NaverPriceSearch.checkAccess(SEARCH_URL,403,"보안 확인"))
            .isInstanceOfSatisfying(NaverPriceSearch.SearchBlocked.class,failure->assertThat(NaverSearchFailure.temporary(failure.reason().name(),failure.httpStatus())).isFalse());
    }

    @Test void permitsNormalResultsAndEmptyResults() {
        assertThatCode(()->NaverPriceSearch.checkAccess(SEARCH_URL,200,"상품명 · 헤지스 · 69,000원"))
            .doesNotThrowAnyException();
        assertThatCode(()->NaverPriceSearch.checkAccess(SEARCH_URL,200,"검색 결과가 없습니다."))
            .doesNotThrowAnyException();
    }
}
