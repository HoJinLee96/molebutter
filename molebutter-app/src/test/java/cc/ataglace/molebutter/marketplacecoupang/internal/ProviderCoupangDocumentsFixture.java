package cc.ataglace.molebutter.marketplacecoupang.internal;

import tools.jackson.databind.ObjectMapper;
import java.util.List;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing;
import cc.ataglace.molebutter.marketplace.api.CoupangBrands;
import cc.ataglace.molebutter.marketplace.api.CoupangEditor;
import cc.ataglace.molebutter.marketplace.api.CoupangProductDocuments;
import static org.mockito.Mockito.*;

/** Test-only factory: core contract tests exercise real provider mapping without exporting it. */
public final class ProviderCoupangDocumentsFixture {
    private ProviderCoupangDocumentsFixture() {}
    public static Document apply(Document document,List<MarketplaceEditing.Change> changes){return CoupangEditPatch.apply(document,changes,new ObjectMapper());}
    public static CoupangProductDocuments create(CoupangEditor editor,CoupangBrands brands){return create(editor,brands,"account");}
    public static CoupangProductDocuments create(CoupangEditor editor,cc.ataglace.molebutter.media.api.R2Images r2){var client=mock(CoupangProductClient.class);when(client.registrationUserId()).thenReturn("synthetic-wing");return new DefaultCoupangProductDocuments(editor,mock(CoupangBrands.class),client,new ObjectMapper(),r2);}
    public static CoupangProductDocuments create(CoupangEditor editor,CoupangBrands brands,String account){
        var client=mock(CoupangProductClient.class);when(client.accountKey()).thenReturn(account);when(client.registrationUserId()).thenReturn("synthetic-wing");
        var r2=mock(cc.ataglace.molebutter.media.api.R2Images.class);
        when(r2.resolve(anyLong(),anyString())).thenThrow(new cc.ataglace.molebutter.common.api.InputValidationFailure("R2 공개 이미지 주소를 선택해 주세요."));
        return new DefaultCoupangProductDocuments(editor,brands,client,new ObjectMapper(),r2);
    }
}
