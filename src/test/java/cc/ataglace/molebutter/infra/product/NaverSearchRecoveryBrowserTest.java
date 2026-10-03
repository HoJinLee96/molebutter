package cc.ataglace.molebutter.infra.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import tools.jackson.databind.ObjectMapper;

/** Isolated Chrome with every URL fulfilled/aborted locally; never calls Naver. */
class NaverSearchRecoveryBrowserTest {
    static final String API="https://search.shopping.naver.com/api/search/all?query=HIBA311&sort=price_asc&pagingIndex=1&pagingSize=40";
    @Test void classifiesActualBrowserNoRequestNetworkRedirectMismatchAndSuccess(){
        Assumptions.assumeTrue(Files.exists(Path.of("/Applications/Google Chrome.app")));
        try(var playwright=Playwright.create();var browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))){
            for(String mode:List.of("NONE","NETWORK","REDIRECT","MISMATCH","SCHEMA","SUCCESS","HTTP","PENDING")){
                try(var context=browser.newContext()){
                    context.route("**/*",route->{
                        String url=route.request().url();
                        if(url.contains("/api/search/all")){
                            switch(mode){
                                case "NETWORK" -> route.abort();
                                case "REDIRECT" -> route.fulfill(new Route.FulfillOptions().setStatus(302).setHeaders(Map.of("location","https://nid.naver.com/nidlogin.login")));
                                case "HTTP" -> route.fulfill(new Route.FulfillOptions().setStatus(403).setBody("denied"));
                                case "PENDING" -> { }
                                default -> route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody(mode.equals("SCHEMA")?"{}":NaverGuestSearchTest.DATA.replace("price_asc",mode.equals("MISMATCH")?"review":"price_asc")));
                            }
                        }else if(url.contains("/search/all"))route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<html><body>검색 결과</body></html>"));
                        else route.abort();
                    });
                    Page page=context.newPage();
                    page.navigate("https://search.shopping.naver.com/search/all");
                    var search=new NaverPriceSearch(new ObjectMapper());
                    ReflectionTestUtils.setField(search,"context",context);ReflectionTestUtils.setField(search,"page",page);
                    ReflectionTestUtils.setField(search,"stage","SORT");ReflectionTestUtils.setField(search,"responseTimeoutMillis",500L);
                    ReflectionTestUtils.setField(search,"delay",0L);
                    new NaverBrowserNetwork().install(context,p->{},(p,url)->ReflectionTestUtils.setField(search,"searchFailure",new NaverSearchFailure(NaverSearchFailure.Code.API_LOGIN_REDIRECT,"SORT",Map.of("redirectConfirmed",true))));
                    Runnable click=()->{if(!mode.equals("NONE"))page.evaluate("url => {fetch(url).catch(()=>{});}",API);};
                    if(mode.equals("SUCCESS"))assertThat((Object)ReflectionTestUtils.invokeMethod(search,"capture","HIBA311",1,40,false,click)).isNotNull();
                    else if(mode.equals("HTTP"))assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(search,"capture","HIBA311",1,40,false,click)).isInstanceOfSatisfying(NaverPriceSearch.SearchBlocked.class,f->assertThat(f.httpStatus()).isEqualTo(403));
                    else {
                        var expected=switch(mode){case "NONE"->NaverSearchFailure.Code.SEARCH_NO_REQUEST;case "NETWORK"->NaverSearchFailure.Code.SEARCH_NETWORK_ERROR;case "REDIRECT"->NaverSearchFailure.Code.API_LOGIN_REDIRECT;case "MISMATCH"->NaverSearchFailure.Code.RESPONSE_MISMATCH;case "SCHEMA"->NaverSearchFailure.Code.RESPONSE_SCHEMA_CHANGED;default->NaverSearchFailure.Code.SEARCH_RESPONSE_TIMEOUT;};
                        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(search,"capture","HIBA311",1,40,false,click)).isInstanceOfSatisfying(NaverSearchFailure.class,f->{assertThat(f.code()).as(mode).isEqualTo(expected);assertThat(f.diagnostics().toString()).doesNotContain("HIBA311","https://","Cookie");});
                    }
                    search.close();
                }
            }
        }
    }
}
