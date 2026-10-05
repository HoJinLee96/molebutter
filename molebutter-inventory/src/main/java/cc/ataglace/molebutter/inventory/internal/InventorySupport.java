package cc.ataglace.molebutter.inventory.internal;


import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard;
import cc.ataglace.molebutter.common.api.BusinessRevision;
import cc.ataglace.molebutter.common.api.BusinessTime;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import cc.ataglace.molebutter.identity.api.UserRole;

/** Shared validation and lock order for inventory, independent of procurement lookup. */
abstract class InventorySupport {
    protected final JdbcTemplate jdbc;
    protected final BusinessAccess access;
    protected final CatalogConsistencyGuard guard;
    protected final BusinessTime time;
    protected final ObjectMapper json;
    protected static final long MAX_QUANTITY=1_000_000;
    protected InventorySupport(JdbcTemplate jdbc, BusinessAccess access, CatalogConsistencyGuard guard, BusinessTime time, ObjectMapper json) {
        this.jdbc=jdbc; this.access=access; this.guard=guard; this.time=time; this.json=json;
    }
    public boolean admin(Long actor) {
        return access.productActor(actor,false) == UserRole.ADMIN;
    }
    protected <T> T required(T value,String message) {if(value==null)throw new InputValidationFailure(message);return value;}
    protected static String text(String value,int max) {
        String s=value==null?"":value.trim();if(s.length()>max)throw new InputValidationFailure("입력 길이를 확인해 주세요.");return s;
    }
    protected static Long id(String value) {
        if(value==null||value.isBlank())return null;
        try {long x=Long.parseLong(value);if(x<=0)throw new NumberFormatException();return x;}
        catch(NumberFormatException ex){throw new InputValidationFailure("ID가 올바르지 않습니다.");}
    }
    protected long quantity(Long value) {if(value==null||value<1||value>MAX_QUANTITY)throw new InputValidationFailure("수량은 1~1,000,000의 정수로 입력해 주세요.");return value;}
    protected void price(Long value){if(value!=null&&(value<0||value>1_000_000_000))throw new InputValidationFailure("단가는 0~1,000,000,000원으로 입력해 주세요.");}
    protected void paymentAmount(Long value) {if(value!=null&&(value<0||value>1_000_000_000_000_000L))throw new InputValidationFailure("결제 금액은 0~1,000,000,000,000,000원의 정수로 입력해 주세요.");}
    protected String paymentName(Long method,String legacy,Map<String,Object> current) {
        if(method==null)return text(legacy,100);
        if(current!=null&&Objects.equals(method,current.get("payment_method_id")))return current.get("payment_method").toString();
        var rows=jdbc.queryForList("SELECT name FROM inventory_payment_method WHERE id=? AND deleted_at IS NULL",method);
        if(rows.isEmpty())throw new InputValidationFailure("사용 가능한 결제 수단을 선택해 주세요.");
        return rows.getFirst().get("name").toString();
    }
    protected String url(String value) {
        String s=text(value,2000);if(s.isEmpty())return s;
        try {URI u=URI.create(s);if(!Set.of("http","https").contains(Objects.toString(u.getScheme(),"").toLowerCase(Locale.ROOT))||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException();}
        catch(IllegalArgumentException ex){throw new InputValidationFailure("주문서 URL은 http/https 주소로 입력해 주세요.");}return s;
    }
    protected String request(String value) {
        try {String v=UUID.fromString(value).toString();if(!v.equalsIgnoreCase(value))throw new IllegalArgumentException();return v;}
        catch(Exception ex){throw new InputValidationFailure("요청 식별자가 필요합니다.");}
    }
    protected String hash(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    protected Map<String,Object> one(String query,Object... args) {
        var rows=jdbc.queryForList(query,args);if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND);return rows.getFirst();
    }
    protected long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    protected void version(Map<String,Object> row,Long revision){BusinessRevision.check(number(row,"revision"),revision);}
    protected Map<String,Object> rawItem(long item) {
        // Guard -> request key (if present) -> purchase -> item -> movement. All writers follow this order.
        var link=one("SELECT purchase_id FROM inventory_item WHERE id=?",item);
        activePurchase(one("SELECT deleted_at FROM inventory_purchase WHERE id=? FOR UPDATE",link.get("purchase_id")));
        return one("SELECT * FROM inventory_item WHERE id=? FOR UPDATE",item);
    }
    protected void requestLock(String request) {
        jdbc.update("INSERT INTO inventory_request_lock(request_id) VALUES(?) ON DUPLICATE KEY UPDATE request_id=VALUES(request_id)",request);
    }
    protected void activePurchase(Map<String,Object> row){if(row.get("deleted_at")!=null)throw new OperationFailure("삭제된 구매 주문은 변경할 수 없습니다. 이력만 조회할 수 있습니다.");}
    protected void activeProduct(Long product) {
        required(product,"연결 상품을 선택해 주세요.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE id=? AND merged_into IS NULL AND deleted_at IS NULL",Long.class,product)==0)throw new InputValidationFailure("현재 관리 중인 상품을 선택해 주세요.");
    }
    protected void activeItemProduct(Map<String,Object> row){activeProduct(number(row,"product_id"));}
    protected void paging(int page,int size){if(page<0||!List.of(20,50,100).contains(size))throw new InputValidationFailure("페이지와 표시 개수(20·50·100)를 확인해 주세요.");}
}
