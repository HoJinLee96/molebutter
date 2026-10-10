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
    public static CoupangProductDocuments create(CoupangEditor editor,CoupangBrands brands,String account){
        var client=mock(CoupangProductClient.class);when(client.accountKey()).thenReturn(account);
        return new DefaultCoupangProductDocuments(editor,brands,client,new ObjectMapper());
    }
}
