package cc.ataglace.molebutter.infra.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.*;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class NaverGuestSearchTest {
    final NaverSearchPayload parser=new NaverSearchPayload(new ObjectMapper());
    static final String URL="https://search.shopping.naver.com/api/search/all?query=HIBA311&sort=price_asc&pagingIndex=1&pagingSize=40";
    static final String DATA="""
        {"requestId":"request-1","searchParam":{"query":"HIBA311","sort":"price_asc","pagingIndex":1,"pagingSize":40},
         "appliedSmartPriceSort":false,"shoppingResult":{"total":41,"products":[
           {"item":{"nvMid":"9007199254740993","mallName":"헤지스","mallProductId":"HIBA4F311N2",
             "productName":"가방","price":"69000","deliveryFee":0,"purchaseUrl":"https://www.hazzys.com/product.do"}},
           {"item":{"nvMid":"2","mallName":"카탈로그","mallId":"naver_model","price":"10000"}}]}}
        """;

    @Test void acceptsOnlyTheClickedQueryPageAndSort() {
        var result=parser.match(URL,DATA,"HIBA311",1,40,false);
        assertThat(result).isNotNull();assertThat(result.result().offers()).hasSize(1);
        assertThat(result.result().offers().getFirst().naverProductId()).isEqualTo("9007199254740993");
        assertThat(result.result().offers().getFirst().url()).isEqualTo("https://www.hazzys.com/product.do");
        assertThat(result.result().complete()).isFalse();
        for(String modified:List.of(DATA.replace("HIBA311","OTHER"),DATA.replace("price_asc","rel"),
            DATA.replace("\"pagingIndex\":1","\"pagingIndex\":2"),DATA.replace("\"pagingSize\":40","\"pagingSize\":80"),
            DATA.replace("\"requestId\":\"request-1\"","\"requestId\":\"\""))) {
            assertThat(parser.match(URL,modified,"HIBA311",1,40,false)).isNull();
        }
        assertThat(parser.match(URL.replace("HIBA311","OTHER"),DATA,"HIBA311",1,40,false)).isNull();
        assertThat(parser.match(URL.replace("naver.com/","naver.com.evil.test/"),DATA,"HIBA311",1,40,false)).isNull();
    }

    @Test void refusesSmartPricesUntilTheUiDisablesThem() {
        String smart=DATA.replace("\"appliedSmartPriceSort\":false","\"appliedSmartPriceSort\":true");
        assertThat(parser.match(URL,smart,"HIBA311",1,40,null).smartPrice()).isTrue();
        assertThat(parser.match(URL,smart,"HIBA311",1,40,false)).isNull();
        assertThat(parser.match(URL,DATA.replace("\"appliedSmartPriceSort\":false,",""),"HIBA311",1,40,null)).isNull();
    }

    @Test void recognizesLastPageAndLeavesUnknownShippingEmpty() {
        var result=parser.match(URL,DATA.replace("\"total\":41","\"total\":2").replace("\"deliveryFee\":0,",""),"HIBA311",1,40,false);
        assertThat(result.result().complete()).isTrue();assertThat(result.result().offers().getFirst().deliveryFee()).isNull();
        String empty=DATA.substring(0,DATA.indexOf("\"shoppingResult\""))+"\"shoppingResult\":{\"total\":0,\"products\":[]}}";
        assertThat(parser.match(URL,empty,"HIBA311",1,40,false).result().offers()).isEmpty();
        for(String invalid:List.of("null","[]","<html>접속 제한</html>","{}"))assertThat(parser.match(URL,invalid,"HIBA311",1,40,false)).isNull();
    }

    @Test void decodesKoreanQueriesOnce() {
        String query="헤지스 가방";
        assertThat(parser.match(URL.replace("HIBA311","%ED%97%A4%EC%A7%80%EC%8A%A4+%EA%B0%80%EB%B0%A9"),DATA.replace("HIBA311",query),query,1,40,false)).isNotNull();
    }

    @Test void blocksEitherAuthenticationCookieWithoutUsingItsValue() {
        assertThatCode(()->NaverPriceSearch.checkGuestCookies(List.of(new Cookie("NNB","guest")))).doesNotThrowAnyException();
        for(String name:List.of("NID_AUT","NID_SES"))assertThatThrownBy(()->NaverPriceSearch.checkGuestCookies(List.of(new Cookie(name,"test"))))
            .isInstanceOf(NaverPriceSearch.SearchBlocked.class).hasMessageContaining("비로그인");
        assertThat(NaverPriceSearch.isLoginUrl("https://nid.naver.com/nidlogin.login")).isTrue();
        assertThat(NaverPriceSearch.isLoginUrl("https://www.naver.com")).isFalse();
    }

    @Test void chromeIsVisibleAndIsolatedFromPersonalProfiles(@TempDir Path project)throws Exception {
        Path profile=ProductSearchChrome.prepareProfile(project);
        assertThat(profile).isEqualTo(project.toRealPath().resolve("data/product-search-guest"));
        List<String> command=ProductSearchChrome.command(Path.of("/chrome"),profile,9227);
        assertThat(command).contains("--remote-debugging-address=127.0.0.1","--remote-debugging-port=9227","--window-size=1440,900","--disable-extensions");
        assertThat(command).doesNotContain("--remote-debugging-port=0");
        assertThatThrownBy(()->ProductSearchChrome.command(Path.of("/chrome"),profile,0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(command).noneMatch(s->s.contains("headless")||s.contains("product-search-browser")||s.contains("AutomationControlled"));
    }

    @Test void diagnosticsNeverRecordCredentialsOrReferrerQuery() {
        var safe=NaverBrowserNetwork.safeHeaders(Map.of("User-Agent","Chrome test","Accept-Language","ko-KR",
            "Cookie","session=secret","Authorization","Bearer secret","Sec-Fetch-Site","same-site",
            "Referer","https://search.naver.com/search.naver?query=private&token=secret"));
        assertThat(safe).containsEntry("user-agent","Chrome test").containsEntry("accept-language","ko-KR")
            .containsEntry("sec-fetch-site","same-site").containsEntry("referer","search.naver.com/search.naver");
        assertThat(safe.toString()).doesNotContain("secret","private","token","Cookie","Authorization");
    }

    @Test void rejectsProfileSymlinks(@TempDir Path project,@TempDir Path personal)throws Exception {
        Files.createSymbolicLink(project.resolve("data"),personal);
        assertThatThrownBy(()->ProductSearchChrome.prepareProfile(project)).isInstanceOf(IllegalStateException.class);
    }

    @Test void usesPriceComparisonMoreWhenPresentAndShoppingWhenItIsAbsent() {
        NaverPriceSearch search=new NaverPriceSearch(new ObjectMapper());
        Page page=mock(Page.class);BrowserContext context=mock(BrowserContext.class);
        Locator body=mock(Locator.class), moreList=mock(Locator.class), more=mock(Locator.class);
        Locator tabs=mock(Locator.class), shopping=mock(Locator.class);
        ReflectionTestUtils.setField(search,"page",page);ReflectionTestUtils.setField(search,"context",context);
        when(context.cookies()).thenReturn(List.of());when(page.locator("body")).thenReturn(body);
        when(page.locator(NaverPriceSearch.PRICE_MORE)).thenReturn(moreList);
        when(moreList.all()).thenReturn(List.of(more));when(more.isVisible()).thenReturn(true);
        assertThat(search.priceComparisonEntry()).isSameAs(more);
        verify(page,never()).evaluate("window.scrollTo(0, 0)");

        when(moreList.all()).thenReturn(List.of());when(page.mouse()).thenReturn(mock(Mouse.class));
        when(page.locator(NaverPriceSearch.SHOPPING_TAB)).thenReturn(tabs);
        when(tabs.all()).thenReturn(List.of(shopping));when(shopping.isVisible()).thenReturn(true);
        assertThat(search.priceComparisonEntry()).isSameAs(shopping);
        var order=inOrder(page,shopping);
        order.verify(page).evaluate("window.scrollTo(0, 0)");
        order.verify(shopping).scrollIntoViewIfNeeded();
        search.close();
    }
}
