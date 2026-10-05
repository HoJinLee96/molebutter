package db.migration;
import db.migration.ResponsibilityMigration;

import org.flywaydb.core.api.migration.*;

public class V29__separate_catalog_procurement extends BaseJavaMigration {
    @Override public boolean canExecuteInTransaction() { return false; }
    @Override public void migrate(Context context) throws Exception {
        var c=context.getConnection();
        ResponsibilityMigration.idle(c); // Before the first DDL: MySQL DDL cannot be rolled back as a unit.
        ResponsibilityMigration.execute(c,"""
            CREATE TABLE procurement_product (
              product_id BIGINT PRIMARY KEY,
              search_query VARCHAR(255) NOT NULL DEFAULT '', search_mode VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
              managed BOOLEAN NOT NULL DEFAULT TRUE, code_type VARCHAR(30) NOT NULL DEFAULT 'GENERAL', comparison_code VARCHAR(100) NOT NULL DEFAULT '',
              lookup_revision BIGINT NOT NULL DEFAULT 0, change_version BIGINT NOT NULL DEFAULT 0,
              latest_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CHECKED', latest_result JSON NULL, last_good_result JSON NULL,
              latest_at DATETIME(6) NULL, image_url VARCHAR(2000) NULL,
              CONSTRAINT fk_procurement_product FOREIGN KEY(product_id) REFERENCES catalog_product(id),
              INDEX ix_procurement_comparison(comparison_code,product_id), INDEX ix_procurement_status(latest_status,product_id)
            )
            """);
        ResponsibilityMigration.execute(c,"""
            CREATE TABLE procurement_settings (
              id INT PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0,
              schedule_enabled BOOLEAN NOT NULL DEFAULT TRUE, schedule_time VARCHAR(5) NOT NULL DEFAULT '18:00',
              preference_revision BIGINT NOT NULL DEFAULT 0
            )
            """);
        ResponsibilityMigration.execute(c,"""
            CREATE TABLE procurement_runtime (
              id INT PRIMARY KEY, last_schedule_date DATE NULL, worker_owner VARCHAR(100) NULL, worker_until DATETIME(6) NULL,
              next_search_at DATETIME(6) NULL, stock_lookup_blocked_job BIGINT NULL, search_cooldown_until DATETIME(6) NULL,
              search_manual_resume_required BOOLEAN NOT NULL DEFAULT FALSE, search_gate_attempt_id BIGINT NULL,
              search_gate_run_id BIGINT NULL, search_gate_version BIGINT NOT NULL DEFAULT 0
            )
            """);
        ResponsibilityMigration.execute(c,"CREATE TABLE catalog_consistency_guard (id INT PRIMARY KEY)");
        ResponsibilityMigration.copy(c,"procurement_product","catalog_product","product_id",ResponsibilityMigration.PRODUCT);
        ResponsibilityMigration.copy(c,"procurement_settings","product_settings","id",ResponsibilityMigration.SETTINGS);
        ResponsibilityMigration.copy(c,"procurement_runtime","product_settings","id",ResponsibilityMigration.RUNTIME);
        ResponsibilityMigration.execute(c,"INSERT INTO catalog_consistency_guard(id) VALUES(1)");
        ResponsibilityMigration.verifyAll(c);
    }
}
