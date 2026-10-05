package cc.ataglace.molebutter.inventory.internal;
import cc.ataglace.molebutter.inventory.api.InventoryDtos.*;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;


class InventoryInputTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"1.9","1e2","true","\"2\"","9223372036854775808"})
    void rejectsFractionalAndCoercedInventoryNumbers(String value){
        assertThatThrownBy(()->mapper.readValue("{\"quantity\":"+value+"}",MovementInput.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->mapper.readValue("{\"unitPrice\":"+value+"}",ItemInput.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->mapper.readValue("{\"paymentAmount\":"+value+"}",PurchaseInput.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
    }
    @Test void allowsIntegerZeroAndMissingAmountsWithoutConflatingThem(){
        assertThat(mapper.readValue("{\"orderedQuantity\":2,\"unitPrice\":0}",ItemInput.class).unitPrice()).isZero();
        assertThat(mapper.readValue("{\"orderedQuantity\":2,\"unitPrice\":null}",ItemInput.class).unitPrice()).isNull();
        assertThat(mapper.readValue("{\"quantity\":2,\"revision\":0}",MovementInput.class).quantity()).isEqualTo(2L);
    }
    @Test void inventoryContractsExcludeUnknownColumnsAndSeparateAdministrativeFields(){
        var row=new java.util.LinkedHashMap<String,Object>();
        row.put("id","1");row.put("productId","2");row.put("onHand",0L);row.put("unitPrice",0L);
        row.put("remainingAmount","0");row.put("orderUrl","https://private.test/order");
        row.put("futurePaymentSecret","must-not-leak");row.put("privateNote","private");
        for(var view:cc.ataglace.molebutter.inventory.internal.InventoryView.values()) {
            assertThat(view.project(row,false)).doesNotContainKeys("unitPrice","remainingAmount","orderUrl","privateNote","futurePaymentSecret");
            assertThat(view.project(row,true)).doesNotContainKey("futurePaymentSecret");
        }
        assertThat(cc.ataglace.molebutter.inventory.internal.InventoryView.ITEM.project(row,true)).containsEntry("unitPrice",0L);
    }
}
