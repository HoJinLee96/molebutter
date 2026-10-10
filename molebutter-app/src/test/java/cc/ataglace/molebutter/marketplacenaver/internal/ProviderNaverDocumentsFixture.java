package cc.ataglace.molebutter.marketplacenaver.internal;

import cc.ataglace.molebutter.marketplace.api.NaverCatalog;
import cc.ataglace.molebutter.marketplace.api.NaverProductDocuments;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;
import static org.mockito.Mockito.*;

/** Test-only factory; provider implementations remain hidden from application main code. */
public final class ProviderNaverDocumentsFixture {
    private ProviderNaverDocumentsFixture() {}
    public static NaverProductDocuments create(String account){return create(mock(NaverCatalog.class),account);}
    public static NaverProductDocuments create(NaverCatalog catalog,String account){
        var gateway=mock(NaverGateway.class);when(gateway.accountKey()).thenReturn(account);
        return new DefaultNaverProductDocuments(catalog,gateway);
    }
}
