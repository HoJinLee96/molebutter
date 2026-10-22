package cc.ataglace.molebutter.app.internal;

import java.sql.SQLException;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import db.migration.V49__coupang_registered_products;

/** Refuse an offline rollout before Flyway records a failed migration. */
@Configuration(proxyBeanMethods = false)
public class CoupangMigrationConfiguration {
    @Bean
    public FlywayMigrationStrategy coupangMigrations() {
        return flyway -> {
            if (flyway.getConfiguration().getTarget().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("49")) < 0) {
                flyway.migrate(); return;
            }
            try (var c = flyway.getConfiguration().getDataSource().getConnection(); var s = c.createStatement()) {
                boolean history;
                try (var rows = s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'")) {
                    rows.next(); history = rows.getLong(1) == 1;
                }
                if (history) {
                    boolean previous = false, applied = false, failed = false;
                    try (var rows = s.executeQuery("SELECT version,success FROM flyway_schema_history WHERE version IN ('48','49')")) {
                        while (rows.next()) {
                            if ("48".equals(rows.getString(1)) && rows.getBoolean(2)) previous = true;
                            if ("49".equals(rows.getString(1))) { applied |= rows.getBoolean(2); failed |= !rows.getBoolean(2); }
                        }
                    }
                    if (failed) throw new IllegalStateException("V49 실패 기록이 있습니다. 앱을 중지하고 scripts/upgrade-coupang-products.sh --apply <새 백업 디렉터리>로 검증 복구하세요. 자동 repair는 실행하지 않습니다.");
                    if (previous && !applied) {
                        V49__coupang_registered_products.check(c);
                        V49__coupang_registered_products.requireVerifiedBackup(c);
                    }
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("V49 기동 사전 검사 중단. 앱·워커 중지와 검증된 오프라인 전환이 필요합니다.", failure);
            }
            if(flyway.getConfiguration().getTarget().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("50"))>=0){
                try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coupang_registered_product'")){
                    r.next();if(r.getLong(1)>0){
                        try(var q=c.createStatement();var history=q.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='50' AND success=TRUE")){
                            history.next();if(history.getLong(1)==0){db.migration.V50__coupang_pricing_settings.check(c);db.migration.V50__coupang_pricing_settings.requireVerifiedBackup(c);}
                        }
                    }
                }catch(SQLException failure){throw new IllegalStateException("V50: 앱·워커 중지 후 scripts/upgrade-coupang-pricing.sh --apply <새 백업 디렉터리>로 전환하세요.",failure);}
            }
            if(flyway.getConfiguration().getTarget().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("51"))>=0){
                try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coupang_pricing_settings'")){
                    r.next();if(r.getLong(1)>0){try(var q=c.createStatement();var history=q.executeQuery("SELECT version,success FROM flyway_schema_history WHERE version='51'")){
                        boolean applied=false;while(history.next()){if(!history.getBoolean(2))throw new SQLException("V51 failed history; restore verified backup, never auto repair");applied=true;}
                        if(!applied){db.migration.V51__coupang_registration_defaults.check(c);db.migration.V51__coupang_registration_defaults.requireVerifiedBackup(c);}
                    }}
                }catch(SQLException failure){throw new IllegalStateException("V51: 앱·워커 중지 후 scripts/upgrade-coupang-registration.sh --apply <새 백업 디렉터리>로 전환하세요.",failure);}
            }
            if(flyway.getConfiguration().getTarget().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("52"))>=0) {
                try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coupang_registration_defaults'")) {
                    r.next();if(r.getLong(1)>0)try(var q=c.createStatement();var history=q.executeQuery("SELECT success FROM flyway_schema_history WHERE version='52'")) {
                        boolean applied=false;while(history.next()){if(!history.getBoolean(1))throw new SQLException("V52 failed history; restore verified backup, never auto repair");applied=true;}
                        if(!applied){db.migration.V52__coupang_recent_product_collections.check(c);db.migration.V52__coupang_recent_product_collections.requireVerifiedBackup(c);}
                    }
                }catch(SQLException failure){throw new IllegalStateException("V52: 앱·워커 중지 후 scripts/upgrade-coupang-recent-products.sh --apply <새 백업 디렉터리>로 전환하세요.",failure);}
            }
            flyway.migrate();
        };
    }
}
