package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.internal.NaverBrowserNetwork;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;

class NaverBrowserNetworkTest {
    @Test void protectsExistingAndNewTabsWithoutRewritingRequests() {
        BrowserContext context=mock(BrowserContext.class);
        Page existing=mock(Page.class), popup=mock(Page.class);
        CDPSession first=mock(CDPSession.class), second=mock(CDPSession.class);
        when(context.pages()).thenReturn(List.of(existing));
        when(context.newCDPSession(existing)).thenReturn(first);
        when(context.newCDPSession(popup)).thenReturn(second);
        AtomicBoolean login=new AtomicBoolean();
        new NaverBrowserNetwork().install(context,page->login.set(true));
        Consumer<Page> pageListener=capturePageListener(context);
        pageListener.accept(popup);
        for(CDPSession session:List.of(first,second)) {
            ArgumentCaptor<JsonObject> options=ArgumentCaptor.forClass(JsonObject.class);
            verify(session).send(eq("Network.setBlockedURLs"),options.capture());
            assertThat(options.getValue().getAsJsonArray("urls").asList())
                .extracting(value->value.getAsString()).containsExactly("http://nid.naver.com/*","https://nid.naver.com/*");
        }
        Consumer<Request> requests=captureRequestListener(context);
        Request normal=mock(Request.class), auth=mock(Request.class);
        when(normal.url()).thenReturn("https://search.shopping.naver.com/search/all");
        when(auth.url()).thenReturn("https://nid.naver.com/nidlogin.login");
        requests.accept(normal); assertThat(login.get()).isFalse();
        requests.accept(auth); assertThat(login.get()).isFalse(); // subresource is not a login screen
        Frame frame=mock(Frame.class);when(auth.frame()).thenReturn(frame);when(frame.page()).thenReturn(existing);
        when(auth.isNavigationRequest()).thenReturn(true);
        requests.accept(auth); assertThat(login.get()).isTrue();
        // 검색 문서의 캐시·기본 헤더를 바꾸는 interception을 다시 도입하지 않는다.
        assertThat(mockingDetails(context).getInvocations()).noneMatch(invocation ->
            List.of("route","setExtraHTTPHeaders","addInitScript").contains(invocation.getMethod().getName()));
        pageListener.accept(popup); verify(context,times(1)).newCDPSession(popup);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static Consumer<Page> capturePageListener(BrowserContext context) {
        ArgumentCaptor<Consumer<Page>> listener=ArgumentCaptor.forClass((Class)Consumer.class);
        verify(context).onPage(listener.capture()); return listener.getValue();
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Consumer<Request> captureRequestListener(BrowserContext context) {
        ArgumentCaptor<Consumer<Request>> listener=ArgumentCaptor.forClass((Class)Consumer.class);
        verify(context).onRequest(listener.capture()); return listener.getValue();
    }
}
