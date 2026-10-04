package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.service.common.BusinessTime;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.exception.*;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@Component
public class ProductStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final BusinessTime time;
    private final cc.ataglace.molebutter.service.common.BusinessAccess access;
    private final cc.ataglace.molebutter.service.common.CatalogConsistencyGuard guard;
    @org.springframework.beans.factory.annotation.Autowired
    public ProductStore(JdbcTemplate jdbc,ObjectMapper json,BusinessTime time,
            cc.ataglace.molebutter.service.common.BusinessAccess access,
            cc.ataglace.molebutter.service.common.CatalogConsistencyGuard guard) {
        this.jdbc=jdbc;this.json=json;this.time=time;this.access=access;this.guard=guard;
    }
    /** Offline repair entrypoints supply their own transaction and use the same policies. */
    public ProductStore(JdbcTemplate jdbc,ObjectMapper json,BusinessTime time) {
        this(jdbc,json,time,new cc.ataglace.molebutter.service.common.BusinessAccess(jdbc),
                new cc.ataglace.molebutter.service.common.CatalogConsistencyGuard(jdbc));
    }
    public static long id(){return cc.ataglace.molebutter.service.common.BusinessIds.next();}
    String encode(Object value){return json.writeValueAsString(value);}
    <T>T decode(String value,Class<T> type){return value==null?null:json.readValue(value,type);}
    public ScheduleSettings schedule(boolean lock){return jdbc.queryForObject("SELECT * FROM product_settings WHERE id=1"+(lock?" FOR UPDATE":""),(r,n)->new ScheduleSettings(r.getLong("revision"),r.getBoolean("schedule_enabled"),r.getString("schedule_time")));}
    // 상품 변경과 실행 결과 확정은 같은 잠금 순서를 사용한다.
    public void lock(){guard.exclusive();}
    public void authorize(Long actor,boolean adminOnly) {
        access.productActor(actor,adminOnly);
    }
    static LocalDateTime date(ResultSet r,String name)throws SQLException{var t=r.getTimestamp(name);return t==null?null:t.toLocalDateTime();}
    public static void revision(long current,Long requested){cc.ataglace.molebutter.service.common.BusinessRevision.check(current,requested);}
    static String text(String value,int max,boolean required){String s=value==null?"":value.trim();if(s.length()>max||required&&s.isEmpty())throw new InputValidationFailure("필수 입력값과 입력 길이를 확인해 주세요.");return s;}
    public static List<Long> ids(List<String> ids) { return cc.ataglace.molebutter.service.common.BusinessIds.parseList(ids); }
}
