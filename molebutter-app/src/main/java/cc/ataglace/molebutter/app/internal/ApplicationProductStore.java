package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.common.api.BusinessTime;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApplicationProductStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final BusinessTime time;
    private final cc.ataglace.molebutter.identity.api.BusinessAccess access;
    private final cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard guard;
    @org.springframework.beans.factory.annotation.Autowired
    public ApplicationProductStore(JdbcTemplate jdbc,ObjectMapper json,BusinessTime time,
            cc.ataglace.molebutter.identity.api.BusinessAccess access,
            cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard guard) {
        this.jdbc=jdbc;this.json=json;this.time=time;this.access=access;this.guard=guard;
    }
    public static long id(){return cc.ataglace.molebutter.common.api.BusinessIds.next();}
    public String encode(Object value){return json.writeValueAsString(value);}
    public <T>T decode(String value,Class<T> type){return value==null?null:json.readValue(value,type);}
    // 상품 변경과 실행 결과 확정은 같은 잠금 순서를 사용한다.
    public void lock(){guard.exclusive();}
    public void authorize(Long actor,boolean adminOnly) {
        access.productActor(actor,adminOnly);
    }
    static LocalDateTime date(ResultSet r,String name)throws SQLException{var t=r.getTimestamp(name);return t==null?null:t.toLocalDateTime();}
    public static void revision(long current,Long requested){cc.ataglace.molebutter.common.api.BusinessRevision.check(current,requested);}
    public static String text(String value,int max,boolean required){String s=value==null?"":value.trim();if(s.length()>max||required&&s.isEmpty())throw new InputValidationFailure("필수 입력값과 입력 길이를 확인해 주세요.");return s;}
    public static List<Long> ids(List<String> ids) { return cc.ataglace.molebutter.common.api.BusinessIds.parseList(ids); }
}
