package cc.ataglace.molebutter.app.internal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ataglace.molebutter.coupang.api.CoupangSearchTags;
import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import java.util.List;
import org.junit.jupiter.api.Test;
class CoupangSearchTagsControllerTest {
    @Test void editPermissionIsCheckedBeforeGeneration(){
        var access=mock(BusinessAccess.class);var tags=mock(CoupangSearchTags.class);var actor=mock(UserPrincipal.class);when(actor.userId()).thenReturn(7L);
        var input=new CoupangSearchTags.Input("지갑","닥스",new CoupangSearchTags.Context("","","",List.of()));
        var controller=new CoupangSearchTagsController(tags,access);var result=new CoupangSearchTags.Result(List.of("지갑"),List.of());when(tags.generate(input)).thenReturn(result);
        assertThat(controller.generate(actor,input).getData()).isEqualTo(result);verify(access).productActor(7L,true);
        clearInvocations(tags);doThrow(new InputValidationFailure("권한 없음")).when(access).productActor(7L,true);
        assertThatThrownBy(()->controller.generate(actor,input)).hasMessageContaining("권한 없음");verifyNoInteractions(tags);
    }
}
