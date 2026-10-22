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

class CoupangUnifiedViewTest {
    MockMvc http;
    @BeforeEach void setup(){var accounts=mock(IdentityAccounts.class);var account=mock(UserAccount.class);when(account.getName()).thenReturn("관리자");when(accounts.findById(42L)).thenReturn(Optional.of(account));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(42L,"fixture@example.com",UserRole.ADMIN),null,List.of()));http=MockMvcBuilders.standaloneSetup(new ViewController(accounts)).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void productListHasProductSpecificRoute()throws Exception{
        http.perform(get("/coupang/products")).andExpect(status().isOk()).andExpect(view().name("coupang"));
        http.perform(get("/coupang")).andExpect(status().isNotFound());
    }
    @Test void nativeProductAndWorkingCopyRoutesUseOneEditor()throws Exception{
        for(String path:List.of("/coupang/products/new","/coupang/products/123/edit","/coupang/working-copies/42/edit"))http.perform(get(path)).andExpect(status().isOk()).andExpect(view().name("coupang-editor"));
        for(String path:List.of("/coupang/drafts/new","/coupang/drafts/42/edit"))http.perform(get(path)).andExpect(status().isNotFound());
    }
    @Test void removedSellerRoutesAreNotMapped()throws Exception{
        for(String path:List.of("/marketplaces","/marketplaces/products/new","/marketplaces/coupang/products/new","/marketplace-orders","/marketplace-images/token","/coupang/naver/products/new","/coupang/esm/products/new","/coupang/lotteon/products/new","/coupang/elevenst/products/new"))http.perform(get(path)).andExpect(status().isNotFound());
    }
}
