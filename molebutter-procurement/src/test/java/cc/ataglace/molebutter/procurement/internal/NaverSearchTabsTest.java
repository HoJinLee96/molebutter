package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.internal.NaverPriceSearch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.microsoft.playwright.*;

class NaverSearchTabsTest {
    @Test void preservesUnrelatedTabsAndCreatesBlankBeforeClosingSearchTabs(){
        var context=mock(BrowserContext.class);
        Page search=tab("https://search.shopping.naver.com/search/all"),login=tab("https://nid.naver.com/nidlogin.login"),other=tab("https://example.com"),blank=tab("about:blank");
        when(context.pages()).thenReturn(List.of(search,login,other),List.of(search,login,other,blank));
        when(context.newPage()).thenReturn(blank);
        Set<Page> owned=new HashSet<>(List.of(search));
        NaverPriceSearch.closeSearchTabs(context,owned);
        var order=inOrder(context,search,login);order.verify(context).newPage();order.verify(search).close();order.verify(login).close();
        verify(other,never()).close();verify(blank,never()).close();assertThat(owned).isEmpty();
    }
    @Test void reusesBlankAndClosesOwnedPopupEvenBeforeItsNavigationCompletes(){
        var context=mock(BrowserContext.class);Page blank=tab("about:blank"),popup=tab("about:blank");
        when(context.pages()).thenReturn(List.of(blank,popup));
        NaverPriceSearch.closeSearchTabs(context,new HashSet<>(List.of(popup)));
        verify(context,never()).newPage();verify(blank,never()).close();verify(popup).close();
        assertThat(NaverPriceSearch.isNaverUrl("https://naver.com.attacker.test")).isFalse();
    }
    @Test void cleanupFailureIsNotSilentlyIgnored(){
        var context=mock(BrowserContext.class);Page blank=tab("about:blank"),page=tab("https://www.naver.com");
        when(context.pages()).thenReturn(List.of(blank,page));doThrow(new PlaywrightException("closed connection")).when(page).close();
        assertThatThrownBy(()->NaverPriceSearch.closeSearchTabs(context,new HashSet<>())).isInstanceOf(PlaywrightException.class);
    }
    private Page tab(String url){Page p=mock(Page.class);when(p.url()).thenReturn(url);return p;}
}
