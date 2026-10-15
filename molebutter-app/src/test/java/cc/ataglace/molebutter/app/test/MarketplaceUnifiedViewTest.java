package cc.ataglace.molebutter.app.test;

import java.util.*;
import cc.ataglace.molebutter.app.internal.ViewController;
import cc.ataglace.molebutter.identity.api.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MarketplaceUnifiedViewTest {
    MockMvc http;
    @BeforeEach void setup(){var accounts=mock(IdentityAccounts.class);var account=mock(UserAccount.class);when(account.getName()).thenReturn("관리자");when(accounts.findById(42L)).thenReturn(Optional.of(account));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(42L,"fixture@example.com",UserRole.ADMIN),null,List.of()));http=MockMvcBuilders.standaloneSetup(new ViewController(accounts)).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void everyExistingRouteUsesTheSameHtmlWithServerSuppliedPurpose()throws Exception{
        for(String market:List.of("coupang","naver","elevenst","lotteon")){
            String key=market.toUpperCase(java.util.Locale.ROOT);
            http.perform(get("/marketplaces/"+market+"/products/new").param("draftId","77")).andExpect(status().isOk()).andExpect(view().name("common-marketplace-editor")).andExpect(model().attribute("editorMode","MARKET_NEW")).andExpect(model().attribute("editorMarket",key)).andExpect(model().attribute("editorDraftId","77"));
            http.perform(get("/marketplaces/"+market+"/products/123/edit")).andExpect(view().name("common-marketplace-editor")).andExpect(model().attribute("editorMode","MARKET_EDIT")).andExpect(model().attribute("editorProductId","123"));
        }
        http.perform(get("/marketplaces/products/new")).andExpect(view().name("common-marketplace-editor")).andExpect(model().attribute("editorMode","COMMON_NEW"));
        http.perform(get("/marketplaces/products/42/edit")).andExpect(view().name("common-marketplace-editor")).andExpect(model().attribute("editorMode","COMMON_EDIT")).andExpect(model().attribute("editorDraftId","42"));
    }
}
