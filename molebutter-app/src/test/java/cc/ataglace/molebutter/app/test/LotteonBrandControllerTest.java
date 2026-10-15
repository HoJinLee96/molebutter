package cc.ataglace.molebutter.app.test;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import cc.ataglace.molebutter.app.internal.LotteonBrandController;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.marketplacelotteon.api.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LotteonBrandControllerTest {
    private final LotteonBrands brands=mock(LotteonBrands.class);
    private MockMvc http;
    @BeforeEach void setup(){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(42L,"fixture@example.com",UserRole.ADMIN),null,List.of()));
        http=MockMvcBuilders.standaloneSetup(new LotteonBrandController(brands)).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void searchUsesAuthenticatedActorAndReturnsOnlyPublicBrandFields()throws Exception{
        when(brands.search(42L,"닥스")).thenReturn(List.of(new LotteonBrands.Brand("P134","닥스")));
        http.perform(get("/api/marketplaces/lotteon/metadata/brands").param("name","닥스").param("actor","999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("P134")).andExpect(jsonPath("$.data[0].name").value("닥스"));
        verify(brands).search(42L,"닥스");
    }
    @Test void queryValidationHasStructuredError()throws Exception{
        when(brands.search(42L,"")).thenThrow(new InputValidationFailure("브랜드명을 입력해 주세요."));
        http.perform(get("/api/marketplaces/lotteon/metadata/brands").param("name",""))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    @Test void configurationAndRateLimitFailuresHaveMarketSpecificCodes()throws Exception{
        when(brands.search(42L,"닥스")).thenThrow(new LotteonBrandFailure(LotteonBrandFailure.Kind.CONFIGURATION));
        http.perform(get("/api/marketplaces/lotteon/metadata/brands").param("name","닥스"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LOTTEON_CONFIGURATION"));
        doThrow(new LotteonBrandFailure(LotteonBrandFailure.Kind.RATE_LIMIT)).when(brands).search(42L,"닥스");
        http.perform(get("/api/marketplaces/lotteon/metadata/brands").param("name","닥스"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOTTEON_RATE_LIMIT"));
    }
}
