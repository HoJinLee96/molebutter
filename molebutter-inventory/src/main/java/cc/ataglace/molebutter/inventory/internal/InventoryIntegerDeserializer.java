package cc.ataglace.molebutter.inventory.internal;


import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** Inventory counts and KRW amounts must never silently truncate fractional JSON values. */
public class InventoryIntegerDeserializer extends StdDeserializer<Long> {
    public InventoryIntegerDeserializer(){super(Long.class);}
    @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws JacksonException {
        if(parser.currentToken()==JsonToken.VALUE_NUMBER_INT)return parser.getLongValue();
        return context.reportInputMismatch(Long.class,"수량·금액·버전은 정수로 입력해 주세요.");
    }
}
