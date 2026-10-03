package cc.ataglace.molebutter.service.product;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.exception.*;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class ProductStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final ProductTime time;
    public static long id(){return com.github.f4b6a3.tsid.TsidCreator.getTsid().toLong();}
    String encode(Object value){return json.writeValueAsString(value);}
    <T>T decode(String value,Class<T> type){return value==null?null:json.readValue(value,type);}
    public ScheduleSettings schedule(boolean lock){return jdbc.queryForObject("SELECT * FROM product_settings WHERE id=1"+(lock?" FOR UPDATE":""),(r,n)->new ScheduleSettings(r.getLong("revision"),r.getBoolean("schedule_enabled"),r.getString("schedule_time")));}
    // 상품 변경과 실행 결과 확정은 같은 잠금 순서를 사용한다.
    public void lock(){schedule(true);}
    public void authorize(Long actor,boolean adminOnly) {
        var rows=jdbc.queryForList("SELECT user_role,user_status FROM `user` WHERE id=?",actor);
        if(rows.isEmpty()||!rows.getFirst().get("user_status").equals("ACTIVE")||!(rows.getFirst().get("user_role").equals("ADMIN")||!adminOnly&&rows.getFirst().get("user_role").equals("PRODUCT")))throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
    }
    static LocalDateTime date(ResultSet r,String name)throws SQLException{var t=r.getTimestamp(name);return t==null?null:t.toLocalDateTime();}
    public static void revision(long current,Long requested){if(requested==null)throw new IllegalArgumentException("수정 버전을 확인해 주세요.");if(current!=requested)throw new cc.ataglace.molebutter.exception.OperationFailure("다른 작업에서 변경되었습니다. 새로 조회해 주세요.");}
    static String text(String value,int max,boolean required){String s=value==null?"":value.trim();if(s.length()>max||required&&s.isEmpty())throw new IllegalArgumentException("필수 입력값과 입력 길이를 확인해 주세요.");return s;}
    public static List<Long> ids(List<String> ids) {
        if(ids==null)return List.of();if(ids.size()>5000)throw new IllegalArgumentException("한 번에 5,000개까지 선택해 주세요.");
        try{return ids.stream().map(Long::valueOf).peek(id->{if(id<=0)throw new IllegalArgumentException("ID가 올바르지 않습니다.");}).distinct().sorted().toList();}
        catch(NumberFormatException e){throw new IllegalArgumentException("ID가 올바르지 않습니다.");}
    }
}
