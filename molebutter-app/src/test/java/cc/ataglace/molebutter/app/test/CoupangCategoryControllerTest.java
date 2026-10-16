package cc.ataglace.molebutter.app.test;

import java.util.List;
import cc.ataglace.molebutter.app.internal.CoupangController;
import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.coupang.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CoupangCategoryControllerTest {
    @Test void commonPickerRouteUsesAuthenticatedActorAndReturnsCategoryChoices() throws Exception {
        var editor=mock(CoupangEditor.class);
        when(editor.categories(42L,"category-test")).thenReturn(List.of(new CoupangEditor.CategoryChoice("69286","패션잡화 > 크로스백")));
        var http=MockMvcBuilders.standaloneSetup(new CoupangController(mock(CoupangCatalog.class),editor,mock(CoupangRequests.class),mock(CoupangBrands.class),mock(CoupangShippingPlaces.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(42L,"fixture@example.com",UserRole.ADMIN),null,List.of()));
        try {
            http.perform(get("/api/coupang/categories").header("X-Coupang-Request-Id","category-test"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("69286"))
                    .andExpect(jsonPath("$.data[0].name").value("패션잡화 > 크로스백"));
            verify(editor).categories(42L,"category-test");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
