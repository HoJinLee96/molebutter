package cc.ataglace.molebutter.marketplacelotteon.internal.product;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LotteonEmptyProductListTest {
    private LotteonCatalog catalog(String body){
        var json=new ObjectMapper();
        var client=new LotteonClient("synthetic-empty-list",json,(method,host,path,request)->{
            assertThat(method+" "+path).isIn("GET /v1/openapi/common/v1/identity","POST /v1/openapi/product/v1/product/list");
            return new LotteonClient.Response(200,path.endsWith("/identity")
                    ?json.writeValueAsBytes(Map.of("returnCode","0000","data",Map.of("trGrpCd","SR","trNo","LO1")))
                    :body.getBytes(java.nio.charset.StandardCharsets.UTF_8),null);
        },0);
        return new LotteonCatalog(client,mock(BusinessAccess.class),json);
    }
    private LotteonProducts.Query query(){return new LotteonProducts.Query(null,null,null,null,null,0,20);}

    @Test void actualZeroCountNullDataIsAnEmptySuccessfulPage(){
        var page=catalog("{\"returnCode\":\"0000\",\"dataCount\":0,\"data\":null}").list(1L,query());
        assertThat(page.items()).isEmpty();assertThat(page.hasNext()).isFalse();assertThat(page.page()).isZero();
    }
    @Test void omittedOptionalDataWithExplicitZeroCountIsAlsoEmpty(){
        assertThat(catalog("{\"returnCode\":\"0000\",\"dataCount\":0}").list(1L,query()).items()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"returnCode\":\"0000\",\"dataCount\":1,\"data\":null}",
        "{\"returnCode\":\"0000\",\"data\":null}",
        "{\"returnCode\":\"0000\",\"dataCount\":0,\"data\":{}}",
        "{\"returnCode\":\"3000\",\"dataCount\":0,\"data\":null}"
    })
    void ambiguousOrRejectedResponsesMustNotLookLikeAnEmptyAccount(String response){
        assertThatThrownBy(()->catalog(response).list(1L,query())).isInstanceOf(MarketplaceFailure.class);
    }
}
