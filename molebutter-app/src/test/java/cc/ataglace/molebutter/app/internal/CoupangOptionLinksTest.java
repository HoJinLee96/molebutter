package cc.ataglace.molebutter.app.internal;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts;
import cc.ataglace.molebutter.identity.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CoupangOptionLinksTest {
    @Test void opensOnlyTheAccountCheckedProductUrlAsANativeRedirect(){
        var products=mock(CoupangRegisteredProducts.class);var controller=new CoupangRegisteredProductController(products);
        when(products.optionProductUrl(1L,"96040603660")).thenReturn("https://www.coupang.com/vp/products/9550561343?vendorItemId=96040603660");
        var response=controller.optionProduct(new UserPrincipal(1L,"fixture@example.test",UserRole.ADMIN),"96040603660");
        assertThat(response.getStatusCode().value()).isEqualTo(302);assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://www.coupang.com/vp/products/9550561343?vendorItemId=96040603660");
        verify(products).optionProductUrl(1L,"96040603660");verifyNoMoreInteractions(products);
    }
}
