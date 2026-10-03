package cc.ataglace.molebutter.infra.product;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.Cookie;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;

/** 비로그인 전용 Chrome에서 홈 → 통합검색 → 더보기/쇼핑 → 낮은 가격순 UI를 사용한다. */
@Component
public class NaverPriceSearch {
    public enum BlockReason { LOGIN_REQUIRED, ACCESS_RESTRICTED, AUTHENTICATED_SESSION, TAB_CLEANUP_FAILED }
    public static class SearchBlocked extends IllegalStateException {
        private final BlockReason reason;
        private final Integer httpStatus;
        public SearchBlocked(String s) { this(BlockReason.ACCESS_RESTRICTED,s); }
        public SearchBlocked(BlockReason reason,String s) { this(reason,s,null); }
        public SearchBlocked(BlockReason reason,String s,Integer status) {super(s);this.reason=reason;this.httpStatus=status;}
        public Integer httpStatus(){return httpStatus;}
        public BlockReason reason(){return reason;}
    }
    private static final String LOW_PRICE = "a[data-shp-contents-id='낮은 가격순'], a:text-is('낮은 가격순'), button:text-is('낮은 가격순')";
    private static final String RANKING = "a[data-shp-contents-id='네이버 랭킹순'], a:text-is('네이버 랭킹순'), button:text-is('네이버 랭킹순')";
    private static final String PAGE_SIZE = "button:has-text('개씩 보기'), a[role='button']:has-text('개씩 보기'), [role='combobox']:has-text('개씩 보기'), span:text-matches('^[0-9]+개씩 보기$')";
    private static final String SMART_PRICE = "a[role='button']:has(svg[class*='svg_radio']), a[role='button'][class*='btn_radio'], label:has-text('적용기준'), button[role='switch'], [class*='switch']:has-text('적용기준')";
    static final String PRICE_MORE = "a:text-matches('가격비교.*더보기')";
    static final String SHOPPING_TAB = "[role='tab']:has-text('쇼핑')";
    private static final String LOGIN_MESSAGE = "[네이버 검색 중단] 로그인 화면으로 전환되어 검색을 중단했습니다. 가격 검색에서는 로그인하지 않습니다. 검색 전용 Chrome을 확인한 뒤 작업 화면에서 재개해 주세요.";
    private final NaverSearchPayload payload;
    private final ProductSearchChrome chrome = new ProductSearchChrome();
    private BrowserContext context;
    private Page page;
    private SearchBlocked blocked;
    private NaverSearchFailure searchFailure;
    private String stage="HOME";
    private String activeQuery;
    private boolean capturing;
    private volatile boolean cleanupRequired;
    private long responseTimeoutMillis=30000;
    private final java.util.concurrent.atomic.AtomicBoolean browserBusy=new java.util.concurrent.atomic.AtomicBoolean();
    private long lastAction;
    private boolean searching;
    private final Set<Page> searchPages=Collections.newSetFromMap(new IdentityHashMap<>());
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(()->{try {r.run();} finally {chrome.close();}},"product-search-browser");
        t.setDaemon(true);return t;
    });
    @Value("${product.search.chrome-executable:/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}") private String executable;
    @Value("${product.search.delay-ms:3000}") private long delay;
    @Value("${product.search.ui-stabilization-ms:2000}") private long stabilization;
    public NaverPriceSearch(ObjectMapper json) { this.payload = new NaverSearchPayload(json); }

    public SearchResult search(String query,Set<String> targetIds,int maxPages) {
        if(query==null||query.isBlank()||query.length()>255)throw new IllegalArgumentException("검색어를 입력해 주세요.");
        if(!browserBusy.compareAndSet(false,true))throw new NaverSearchFailure(NaverSearchFailure.Code.BROWSER_UNAVAILABLE,"BROWSER",Map.of());
        var future=new BrowserTask(()->searchBrowser(query.trim(),Set.copyOf(targetIds),Math.min(3,Math.max(1,maxPages))));
        try {executor.execute(future);}
        catch(RuntimeException e) {
            future.cancel(false);
            if(e instanceof RejectedExecutionException)throw new NaverSearchFailure(NaverSearchFailure.Code.BROWSER_UNAVAILABLE,"BROWSER",Map.of());
            throw e;
        }
        try { return future.get(180,TimeUnit.SECONDS); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof IllegalStateException state)throw state;
            throw new IllegalStateException("검색 화면을 처리하지 못했습니다. 전용 Chrome 창과 서버 로그를 확인해 주세요.",e.getCause());
        }
        catch(InterruptedException e) {cleanupRequired=true;future.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException("검색이 중단되었습니다.");}
        catch(TimeoutException e) {cleanupRequired=true;future.cancel(true);throw new NaverSearchFailure(NaverSearchFailure.Code.SEARCH_RESPONSE_TIMEOUT,"SEARCH",Map.of("overallTimeout",true));}
    }

    /** Future cancellation may complete before browser cleanup actually exits. */
    final class BrowserTask extends FutureTask<SearchResult> {
        private boolean running;
        BrowserTask(Callable<SearchResult> work){super(work);}
        @Override public void run() {
            synchronized(this) {
                if(isCancelled())return;
                running=true;
            }
            try {super.run();}
            finally {browserBusy.set(false);}
        }
        @Override protected synchronized void done() {
            if(!running)browserBusy.set(false);
        }
    }

    private void ensureBrowser() {
        BrowserContext opened = chrome.open(executable,Path.of(""));
        if (context != opened) {
            context=opened; page=null;
            new NaverBrowserNetwork().install(context, source -> {
                if(searching && (searchPages.contains(source)||searchPages.contains(source.opener()))) blocked=new SearchBlocked(BlockReason.LOGIN_REQUIRED,LOGIN_MESSAGE);
            },(source,url)->{
                if(searching&&capturing&&source==page&&activeQuery!=null&&activeQuery.equalsIgnoreCase(NaverSearchPayload.parameter(url,"query")))
                    searchFailure=new NaverSearchFailure(NaverSearchFailure.Code.API_LOGIN_REDIRECT,stage,Map.of("redirectConfirmed",true));
            });
            context.onPage(openedPage -> {if(searching&&searchPages.contains(openedPage.opener()))searchPages.add(openedPage);});
            context.onResponse(response->{
                if(searching && page!=null && response.request().isNavigationRequest() && response.frame().equals(page.mainFrame())) {
                    try { checkAccess(response.url(),response.status(),""); }
                    catch(SearchBlocked e) {blocked=e;}
                }
            });
        }
        checkGuestCookies(context.cookies());
        // All browser operations run on the same executor; no old search tab is reused.
        closeSearchTabs();
        page=context.newPage();searchPages.add(page);
        ProductPublicHeaders.setBrowserUserAgent((String)page.evaluate("navigator.userAgent"));
        page.setDefaultTimeout(30000);page.setDefaultNavigationTimeout(30000);
    }

    private SearchResult searchBrowser(String query,Set<String> targets,int maxPages) {
        blocked=null;searchFailure=null;stage="HOME";activeQuery=query;
        try {
            ensureBrowser();cleanupRequired=false;searching=true;
            enterFromHome(query);
            if(noResults())return new SearchResult(List.of(),true,null);
            stage="PAGE_SIZE";resetPageSize();
            stage="SORT";
            Locator low=required(LOW_PRICE,"낮은 가격순");
            if(isActive(low)) { action();required(RANKING,"네이버 랭킹순").click();waitFor(()->!isActive(required(LOW_PRICE,"낮은 가격순")),"정렬 초기화"); }
            var current=capture(query,1,40,null,()->required(LOW_PRICE,"낮은 가격순").click());
            stage="SMART_PRICE";if(current.smartPrice())current=capture(query,1,40,false,()->required(SMART_PRICE,"가격 적용기준 OFF").click());
            stage="PAGE_SIZE";if(current.total()!=null && current.total()>40)current=capture(query,1,80,false,()->selectPageSize(80));
            stage="PAGING";var result=collectPages(current,maxPages,(next,size)->
                // 추가 페이지도 URL을 만들지 않고 현재 화면의 페이지 버튼을 누른다.
                capture(query,next,size,false,()->required(pageControl(next),next+"페이지").click()));
            checkPage();
            return result;
        } catch(NaverSearchFailure e) {
            throw prepareFailure(e);
        } catch(SearchBlocked e) {
            throw prepareBlock(e);
        } catch(PlaywrightException e) {
            if(blocked!=null)throw prepareBlock(blocked);
            if(searchFailure!=null)throw prepareFailure(searchFailure);
            try {checkPage();} catch(SearchBlocked detected){throw prepareBlock(detected);}catch(NaverSearchFailure detected){throw prepareFailure(detected);}
            throw prepareFailure(new NaverSearchFailure(page==null||page.isClosed()?NaverSearchFailure.Code.BROWSER_UNAVAILABLE:NaverSearchFailure.Code.SEARCH_NETWORK_ERROR,stage,Map.of()));
        } finally {
            searching=false;capturing=false;activeQuery=null;
            if(cleanupRequired){Thread.interrupted();try{closeSearchTabs();cleanupRequired=false;}catch(RuntimeException ignored){}}
        }
    }

    static SearchResult collectPages(NaverSearchPayload.Captured current,int maxPages,
            java.util.function.BiFunction<Integer,Integer,NaverSearchPayload.Captured> nextPage) {
        Map<String,Offer> found=new LinkedHashMap<>();
        for(int n=1;n<=maxPages;n++) {
            current.result().offers().forEach(o->found.putIfAbsent(o.naverProductId(),o));
            if(current.result().complete()||n==maxPages)break;
            current=nextPage.apply(n+1,current.pageSize());
        }
        return new SearchResult(List.copyOf(found.values()),true,null);
    }

    private NaverSearchFailure prepareFailure(NaverSearchFailure failure) {
        searching=false;
        try{closeSearchTabs();return failure;}
        catch(RuntimeException e){return new NaverSearchFailure(NaverSearchFailure.Code.TAB_CLEANUP_FAILED,stage,Map.of());}
    }

    private SearchBlocked prepareBlock(SearchBlocked failure) {
        searching=false;
        if(failure.reason()==BlockReason.LOGIN_REQUIRED) {
            try {closeSearchTabs();}
            catch(RuntimeException e){return new SearchBlocked(BlockReason.TAB_CLEANUP_FAILED,"네이버 검색 탭 정리 실패 · 수동 확인 필요");}
        }
        return failure;
    }
    private void closeSearchTabs() {
        if(context==null)return;
        searching=false;
        closeSearchTabs(context,searchPages);
        page=null;blocked=null;
    }
    static void closeSearchTabs(BrowserContext context,Set<Page> searchPages) {
        // Keep one blank tab so closing the last search tab does not terminate Chrome.
        if(context.pages().stream().noneMatch(p->!p.isClosed()&&"about:blank".equals(p.url())&&!searchPages.contains(p)))context.newPage();
        for(Page old:List.copyOf(context.pages()))if(!old.isClosed()&&(searchPages.contains(old)||isNaverUrl(old.url())))old.close();
        searchPages.clear();
    }
    static boolean isNaverUrl(String url){String h=host(url);return h!=null&&(h.equals("naver.com")||h.endsWith(".naver.com"));}

    private void enterFromHome(String query) {
        // 새 검색 탭에서 홈을 거쳐 시작한다.
        pace();
        page.navigate("https://www.naver.com",new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        checkPage();
        Locator input=required("input#query, input[name='query']","네이버 검색창");
        input.fill(query);action();input.press("Enter");
        waitFor(()->"search.naver.com".equals(host(page.url())),"통합검색 결과");
        settle();
        Locator more=priceComparisonEntry();
        // 기존 구현처럼 실제 링크를 클릭하되 검색 탭 하나를 재사용한다.
        more.evaluate("element => element.setAttribute('target', '_self')");
        Page previous=page;Set<Page> existing=new HashSet<>(context.pages());
        action();more.click();
        waitFor(()->{
            if(isPriceComparison(page.url()))return true;
            for(Page candidate:context.pages())if(!existing.contains(candidate)&&!candidate.isClosed()&&isPriceComparison(candidate.url())) {
                page=candidate;ProductPublicHeaders.setBrowserUserAgent((String)page.evaluate("navigator.userAgent"));
        page.setDefaultTimeout(30000);page.setDefaultNavigationTimeout(30000);previous.close();return true;
            }
            return false;
        },"가격비교 결과");
        if(!query.equalsIgnoreCase(NaverSearchPayload.parameter(page.url(),"query")))
            throw new IllegalStateException("가격비교 화면의 검색어가 요청과 달라 결과를 적용하지 않았습니다.");
        settle();
    }

    Locator priceComparisonEntry() {
        Locator more=null;
        for(int attempt=0;attempt<10;attempt++) {
            checkPage();more=firstVisible(PRICE_MORE);
            if(more!=null)break;
            page.mouse().wheel(0,700);page.waitForTimeout(500);
        }
        if(more==null) {
            // 통합검색에 쇼핑 묶음이 없는 검색어는 상단 쇼핑 탭을 이용한다.
            // 아래로 스크롤하면 네이버가 상단 메뉴를 숨기므로 먼저 맨 위로 돌아간다.
            page.evaluate("window.scrollTo(0, 0)");
            more=required(SHOPPING_TAB,"상단 쇼핑 탭");
        }
        return more;
    }

    private NaverSearchPayload.Captured capture(String query,int number,int size,Boolean smart,Runnable click) {
        Set<Request> started=Collections.newSetFromMap(new IdentityHashMap<>());
        List<NaverSearchPayload.Captured> accepted=new ArrayList<>();
        Set<String> rejected=new TreeSet<>();int[] counts={0,0};Integer[] http={null};
        Consumer<Request> requests=request->{if(NaverSearchPayload.isSearchApi(request.url())&&query.equalsIgnoreCase(NaverSearchPayload.parameter(request.url(),"query")))started.add(request);};
        Consumer<Request> failed=request->{
            Request original=request;for(int i=0;i<12&&original!=null;i++,original=original.redirectedFrom()){
                if(started.contains(original)){
                    if(isLoginUrl(request.url()))searchFailure=new NaverSearchFailure(NaverSearchFailure.Code.API_LOGIN_REDIRECT,stage,Map.of("redirectConfirmed",true));
                    else counts[1]++;
                    break;
                }
            }
        };
        Consumer<Response> responses=response->{
            if(!started.contains(response.request()))return;
            try {
                http[0]=response.status();counts[0]++;
                String location=response.headerValue("location");
                if(response.status()>=300&&response.status()<400&&location!=null&&isLoginUrl(URI.create(response.url()).resolve(location).toString())){
                    searchFailure=new NaverSearchFailure(NaverSearchFailure.Code.API_LOGIN_REDIRECT,stage,Map.of("redirectConfirmed",true),response.status());return;
                }
                checkAccess(response.url(),response.status(),"");
                String text=response.text();
                checkAccess(response.url(),response.status(),text.startsWith("{")?"":text);
                if(response.status()!=200)return;
                if(text.length()>5_000_000){rejected.add("SCHEMA");return;}
                var match=payload.match(response.url(),text,query,number,size,smart);
                if(match!=null)accepted.add(match);
                else rejected.addAll(payload.validationErrors(response.url(),text,query,number,size,smart));
            } catch(SearchBlocked e) {blocked=e;}
            catch(PlaywrightException ignored) {counts[1]++;}
        };
        page.onRequest(requests);page.onResponse(responses);page.onRequestFailed(failed);capturing=true;
        try {
            action();click.run();
            try{waitFor(()->!accepted.isEmpty(),"검색어·정렬·페이지가 일치하는 가격 응답");}
            catch(NaverSearchFailure timeout){
                if(timeout.code()!=NaverSearchFailure.Code.SEARCH_RESPONSE_TIMEOUT)throw timeout;
                var code=!rejected.isEmpty()?(rejected.contains("SCHEMA")||rejected.contains("JSON_FORMAT")?NaverSearchFailure.Code.RESPONSE_SCHEMA_CHANGED:NaverSearchFailure.Code.RESPONSE_MISMATCH)
                    :counts[1]>0||http[0]!=null&&http[0]>=500?NaverSearchFailure.Code.SEARCH_NETWORK_ERROR:started.isEmpty()?NaverSearchFailure.Code.SEARCH_NO_REQUEST:NaverSearchFailure.Code.SEARCH_RESPONSE_TIMEOUT;
                throw new NaverSearchFailure(code,stage,Map.of("requests",started.size(),"responses",counts[0],"networkFailures",counts[1],"expectedPage",number,"expectedSize",size,"mismatches",List.copyOf(rejected)),http[0]);
            }
            checkPage();return accepted.getLast();
        } finally {capturing=false;page.offRequest(requests);page.offResponse(responses);page.offRequestFailed(failed);}
    }

    private void resetPageSize() {
        Locator control=firstVisible(PAGE_SIZE);
        String size=NaverSearchPayload.parameter(page.url(),"pagingSize");
        if(!size.isBlank()&&!"40".equals(size)||control!=null&&!control.innerText().trim().startsWith("40")) {
            action();selectPageSize(40);
            waitFor(()->"40".equals(NaverSearchPayload.parameter(page.url(),"pagingSize")),"40개씩 보기");
        }
    }
    private void selectPageSize(int size) {
        String label=size+"개씩 보기";
        for(Locator select:page.locator("select").all()) {
            if(select.isVisible()&&select.locator("option").allTextContents().stream().map(String::trim).anyMatch(label::equals)) {
                select.selectOption(new SelectOption().setLabel(label));return;
            }
        }
        required(PAGE_SIZE,"표시 개수").click();
        required("a:text-is('"+label+"'), button:text-is('"+label+"'), span:text-is('"+label+"'), [role='option']:has-text('"+label+"')",label).click();
    }
    private static String pageControl(int n) {return "[class*='pagination'] a:text-is('"+n+"'), [class*='pagination'] button:text-is('"+n+"'), a[aria-label='"+n+"'], button[aria-label='"+n+"']";}
    private static boolean isActive(Locator control) {String value=control.getAttribute("class");return value!=null&&value.contains("is_active");}
    private Locator firstVisible(String selector) {for(Locator l:page.locator(selector).all())if(l.isVisible())return l;return null;}
    private Locator required(String selector,String label) {
        try{waitFor(()->firstVisible(selector)!=null,label);}
        catch(NaverSearchFailure failure){if(failure.code()!=NaverSearchFailure.Code.SEARCH_RESPONSE_TIMEOUT)throw failure;throw new NaverSearchFailure(NaverSearchFailure.Code.UI_ELEMENT_MISSING,stage,Map.of());}
        Locator l=firstVisible(selector);l.scrollIntoViewIfNeeded();return l;
    }
    private void waitFor(java.util.function.BooleanSupplier condition,String label) {
        long deadline=System.nanoTime()+Duration.ofMillis(responseTimeoutMillis).toNanos();
        while(System.nanoTime()<deadline) {
            checkPage();if(condition.getAsBoolean())return;page.waitForTimeout(100);
        }
        throw new NaverSearchFailure(NaverSearchFailure.Code.SEARCH_RESPONSE_TIMEOUT,stage,Map.of());
    }
    private void action() {
        checkPage();pace();checkPage();
    }
    private void settle() {
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(Math.max(0,stabilization));
        checkPage();
    }
    private void pace() {
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("검색이 중단되었습니다.");
        if(blocked!=null)throw blocked;
        if(searchFailure!=null)throw searchFailure;
        checkGuestCookies(context.cookies());
        long remaining=lastAction+Math.max(1000,delay)-System.currentTimeMillis();
        if(remaining>0)page.waitForTimeout(remaining);
        lastAction=System.currentTimeMillis();
    }
    private void checkPage() {
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("검색이 중단되었습니다.");
        if(blocked!=null)throw blocked;
        if(searchFailure!=null)throw searchFailure;
        checkGuestCookies(context.cookies());
        if(page==null||page.isClosed())throw new NaverSearchFailure(NaverSearchFailure.Code.BROWSER_UNAVAILABLE,stage,Map.of());
        if(page.locator("body").count()>0)checkAccess(page.url(),200,page.locator("body").innerText());
    }
    private boolean noResults() {String body=page.locator("body").innerText();return body.contains("검색 결과가 없습니다")||body.contains("검색결과가 없습니다");}
    static void checkGuestCookies(List<Cookie> cookies) {
        if(cookies.stream().anyMatch(c->Set.of("NID_AUT","NID_SES").contains(c.name)))
            throw new SearchBlocked(BlockReason.AUTHENTICATED_SESSION,"[네이버 검색 중단] 검색 전용 Chrome에 로그인 상태가 감지되었습니다. 가격 검색은 비로그인 상태에서만 가능합니다. 전용 Chrome의 로그인 상태를 해제한 뒤 작업 화면에서 재개해 주세요.");
    }
    static boolean isLoginUrl(String url) {return "nid.naver.com".equals(host(url));}
    static boolean isPriceComparison(String url) {try{return "search.shopping.naver.com".equals(host(url))&&URI.create(url).getPath().startsWith("/search/");}catch(RuntimeException e){return false;}}
    private static String host(String url) {try{return URI.create(url).getHost();}catch(RuntimeException e){return "";}}
    static void checkAccess(String url,int status,String body) {
        if(isLoginUrl(url)||body.contains("아이디 또는 전화번호")&&body.contains("일회용 번호"))throw new SearchBlocked(BlockReason.LOGIN_REQUIRED,LOGIN_MESSAGE);
        if(status==403||status==418)throw new SearchBlocked(BlockReason.ACCESS_RESTRICTED,"[네이버 검색 제한] HTTP "+status+". 잠시 후 재개해 주세요.",status);
        if(status==429)throw new SearchBlocked(BlockReason.ACCESS_RESTRICTED,"[네이버 검색 제한] 요청 횟수 초과 · HTTP 429. 잠시 후 재개해 주세요.",status);
        if(body.contains("접근이 제한")||body.contains("접속이 일시적으로 제한")||body.contains("비정상적인 접근")||body.contains("보안 확인")||body.contains("자동입력 방지"))
            throw new SearchBlocked("[네이버 검색 제한] 접속 제한·보안 확인 감지. 검색 전용 Chrome을 확인해 주세요.");
    }
    public SearchResult parse(String data) {return payload.parse(data);}
    @PreDestroy void close() {
        executor.shutdownNow();
        try {executor.awaitTermination(40,TimeUnit.SECONDS);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();}
    }
}
