package cc.ataglace.molebutter.catalog.internal;

import javax.sql.DataSource;
import cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;

/** State belongs to the actual transaction, including suspended offline/JPA transactions. */
@Component
@RequiredArgsConstructor
public class JdbcCatalogConsistencyGuard implements CatalogConsistencyGuard {
    private final JdbcTemplate jdbc;

    private static final class Held implements TransactionSynchronization {
        final DataSource source;
        final boolean exclusive;
        Held(DataSource source, boolean exclusive) { this.source = source; this.exclusive = exclusive; }
    }

    private Held held(boolean writable) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Catalog guard requires an actual transaction");
        if (writable && TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Catalog changes require a writable transaction");
        DataSource source = jdbc.getDataSource();
        var connection = DataSourceUtils.getConnection(source);
        try {
            if (!DataSourceUtils.isConnectionTransactional(connection, source) || connection.getAutoCommit())
                throw new IllegalStateException("Catalog guard must use the transaction's DataSource");
        } catch (java.sql.SQLException e) { throw new IllegalStateException("Cannot verify catalog transaction", e); }
        finally { DataSourceUtils.releaseConnection(connection, source); }
        return TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(Held.class::isInstance).map(Held.class::cast)
                .filter(state -> state.source == source).findFirst().orElse(null);
    }

    private void acquire(boolean exclusive) {
        Held current = held(exclusive);
        if (current != null) {
            if (exclusive && !current.exclusive)
                throw new IllegalStateException("Acquire the exclusive catalog guard before any shared guard");
            return;
        }
        var rows = jdbc.queryForList("SELECT id FROM catalog_consistency_guard WHERE id=1 "
                + (exclusive ? "FOR UPDATE" : "FOR SHARE"));
        if (rows.size() != 1) throw new IllegalStateException("Missing catalog consistency guard");
        TransactionSynchronizationManager.registerSynchronization(new Held(jdbc.getDataSource(), exclusive));
    }

    public void exclusive() { acquire(true); }
    public void shared() { acquire(false); }
    public void requireExclusive() {
        Held current = held(true);
        if (current == null || !current.exclusive) throw new IllegalStateException("Exclusive catalog guard required");
    }
    public void requireShared() {
        if (held(false) == null) throw new IllegalStateException("Catalog guard required");
    }
}
