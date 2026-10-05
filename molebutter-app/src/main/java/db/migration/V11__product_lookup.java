package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.*;
import tools.jackson.databind.ObjectMapper;

/** 기존 파일·판매 테이블 제거 전 등록명, 매입처, 옵션 조회 이력을 상품 측으로 보존한다. */
public class V11__product_lookup extends BaseJavaMigration {
    private String sql(){try(var in=getClass().getResourceAsStream("/db/product-lookup-v11.sql")){return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public Integer getChecksum(){return (sql()+"registration-transition-v2").hashCode();}
    @Override public void migrate(Context context)throws Exception {
        Connection c=context.getConnection();
        if(count(c,"SELECT COUNT(*) FROM product_refresh_run WHERE status IN ('RUNNING','PAUSED','BLOCKED')")>0)
            throw new IllegalStateException("상품 구조 전환 전 기존 버전에서 최신화 작업을 완료하거나 취소하고 DB를 백업해 주세요.");
        long catalogs=count(c,"SELECT COUNT(*) FROM catalog_product");
        long options=count(c,"SELECT COUNT(*) FROM purchase_option");
        long entries=count(c,"SELECT COUNT(*) FROM purchase_refresh_item");
        long links=count(c,"SELECT COUNT(*) FROM (SELECT o.product_id,l.mall,l.mall_product_id,l.naver_product_id FROM purchase_source_link l JOIN purchase_option o ON o.id=l.purchase_option_id GROUP BY o.product_id,l.mall,l.mall_product_id,l.naver_product_id) s");
        String[] parts=sql().split("-- CLEANUP");execute(c,parts[0]);
        record Registration(long id,Long product,String code,String name,Timestamp updated){}
        List<Registration> registrations=new ArrayList<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT l.id,o.product_id,l.product_code,l.name,l.updated_at FROM channel_listing l LEFT JOIN purchase_option o ON o.id=l.purchase_option_id ORDER BY l.id")) {
            while(r.next())registrations.add(new Registration(r.getLong(1),r.getObject(2,Long.class),r.getString(3),r.getString(4),r.getTimestamp(5)));
        }
        Map<Long,Set<String>> names=new LinkedHashMap<>();long created=0;
        Set<Long> originalIds=new HashSet<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT id FROM catalog_product")){while(r.next())originalIds.add(r.getLong(1));}
        long next=count(c,"SELECT COALESCE(MAX(id),0) FROM catalog_product");
        for(var r:registrations) {
            Long product=r.product();
            if(product==null) {
                // 같은 ID의 기존 상품을 보존한다. 이름/코드 유사성으로 다른 상품에 연결하지 않는다.
                if(originalIds.contains(r.id()))try(var s=c.prepareStatement("SELECT id FROM catalog_product WHERE id=? AND product_code=?")) {
                    s.setLong(1,r.id());s.setString(2,r.code());try(var found=s.executeQuery()){if(found.next())product=found.getLong(1);}
                }
                if(product==null) {
                    product=++next;created++;
                    try(var s=c.prepareStatement("INSERT INTO catalog_product(id,product_code,search_query,search_mode,created_at,updated_at) VALUES(?,?,?,'AUTO',?,?)")) {
                        s.setLong(1,product);s.setString(2,r.code());s.setString(3,r.code());s.setTimestamp(4,r.updated());s.setTimestamp(5,r.updated());s.executeUpdate();
                    }
                }
            }
            if(r.name()!=null&&!r.name().isBlank())names.computeIfAbsent(product,k->new LinkedHashSet<>()).add(r.name());
        }
        var json=new ObjectMapper();
        for(var e:names.entrySet())try(var s=c.prepareStatement("UPDATE catalog_product SET registration_names=? WHERE id=?")) {
            s.setString(1,json.writeValueAsString(e.getValue()));s.setLong(2,e.getKey());s.executeUpdate();
        }
        if(count(c,"SELECT COUNT(*) FROM catalog_product")!=catalogs+created||count(c,"SELECT COUNT(*) FROM product_lookup_history")!=options+entries||count(c,"SELECT COUNT(*) FROM product_supplier")!=links)
            throw new IllegalStateException("상품 전환 건수 대조에 실패했습니다. 원본 테이블을 제거하지 않았습니다.");
        execute(c,parts[1]);
    }
    private static long count(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    private static void execute(Connection c,String sql)throws SQLException{for(String statement:sql.split(";"))if(!statement.isBlank())try(var s=c.createStatement()){s.execute(statement);}}
}
