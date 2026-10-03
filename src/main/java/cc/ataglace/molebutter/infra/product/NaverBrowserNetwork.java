package cc.ataglace.molebutter.infra.product;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 요청을 가로채거나 헤더를 덮어쓰지 않고 비로그인 조건과 진단 정보를 관리한다. */
final class NaverBrowserNetwork {
    private static final Logger log = LoggerFactory.getLogger(NaverBrowserNetwork.class);
    private final Map<Page, CDPSession> sessions = new IdentityHashMap<>();

    void install(BrowserContext context, Consumer<Page> loginDetected) {install(context,loginDetected,(page,url)->{});}
    void install(BrowserContext context, Consumer<Page> loginDetected,BiConsumer<Page,String> apiLoginDetected) {
        Consumer<Page> protect = page -> protect(context, page,apiLoginDetected);
        context.onPage(protect);
        context.pages().forEach(protect);
        context.onRequest(request -> {
            if (NaverPriceSearch.isLoginUrl(request.url()) && isTopNavigation(request)) loginDetected.accept(request.frame().page());
        });
        context.onResponse(response -> {
            Request request = response.request();
            if (!isNaverDocument(request) && !NaverSearchPayload.isSearchApi(response.url())) return;
            // Cookie/Authorization 값, 전체 Referer URL과 검색어는 로그에 남기지 않는다.
            try {
                log.info("[PRODUCT_SEARCH] status={} target={} headers={}", response.status(),
                    address(response.url()), safeHeaders(request.allHeaders()));
            } catch (PlaywrightException e) {
                log.debug("[PRODUCT_SEARCH] 닫힌 요청의 헤더 진단을 생략합니다.");
            }
        });
    }

    private void protect(BrowserContext context, Page page,BiConsumer<Page,String> apiLoginDetected) {
        if (page.isClosed() || sessions.containsKey(page)) return;
        CDPSession session = context.newCDPSession(page);
        sessions.put(page, session);
        session.send("Network.enable");
        // Chrome can omit redirectResponse when the destination is blocked. The CDP request ID
        // stays the same across the redirect, so retain only bounded search-request origins.
        Map<String,String> searchRequests=new LinkedHashMap<>();
        session.on("Network.requestWillBeSent",event->{
            try{
                String id=event.get("requestId").getAsString();
                String to=event.getAsJsonObject("request").get("url").getAsString();
                String from=searchRequests.get(id);
                if(event.has("redirectResponse")){
                    String redirect=event.getAsJsonObject("redirectResponse").get("url").getAsString();
                    if(NaverSearchPayload.isSearchApi(redirect))from=redirect;
                }
                if(NaverSearchPayload.isSearchApi(to)){
                    searchRequests.put(id,to);
                    if(searchRequests.size()>128)searchRequests.remove(searchRequests.keySet().iterator().next());
                }else if(from!=null&&NaverPriceSearch.isLoginUrl(to)){
                    searchRequests.remove(id);apiLoginDetected.accept(page,from);
                }
            }catch(IllegalStateException|NullPointerException ignored){}
        });
        for(String event:List.of("Network.loadingFinished","Network.loadingFailed"))session.on(event,value->{if(value.has("requestId"))searchRequests.remove(value.get("requestId").getAsString());});
        // Playwright route()는 전체 HTTP 캐시를 끈다. Chrome의 URL 차단만 사용한다.
        JsonArray urls = new JsonArray();
        urls.add("http://nid.naver.com/*"); urls.add("https://nid.naver.com/*");
        JsonObject options = new JsonObject(); options.add("urls", urls);
        session.send("Network.setBlockedURLs", options);
        page.onClose(ignored -> sessions.remove(page));
    }

    static boolean isTopNavigation(Request request) {
        try {return request.isNavigationRequest() && request.frame().parentFrame()==null;}
        catch(PlaywrightException e){return false;}
    }
    private static boolean isNaverDocument(Request request) {
        try {
            String host = URI.create(request.url()).getHost();
            return request.isNavigationRequest() && request.frame().parentFrame() == null && host != null
                && (host.equals("naver.com") || host.endsWith(".naver.com"));
        } catch (IllegalArgumentException | PlaywrightException e) { return false; }
    }

    static Map<String,String> safeHeaders(Map<String,String> headers) {
        Map<String,String> safe = new TreeMap<>();
        Set<String> allowed = Set.of("user-agent", "accept-language", "sec-ch-ua", "sec-ch-ua-mobile",
            "sec-ch-ua-platform", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-user");
        headers.forEach((key,value) -> {
            String name = key.toLowerCase(Locale.ROOT);
            if (allowed.contains(name)) safe.put(name, value);
            if (name.equals("referer")) safe.put(name, address(value));
        });
        return safe;
    }

    private static String address(String url) {
        try { URI uri = URI.create(url); return uri.getHost() + Optional.ofNullable(uri.getPath()).orElse(""); }
        catch (IllegalArgumentException e) { return "invalid"; }
    }
}
