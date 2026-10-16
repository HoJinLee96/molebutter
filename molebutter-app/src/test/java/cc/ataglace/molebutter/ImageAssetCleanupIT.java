package cc.ataglace.molebutter;

import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import cc.ataglace.molebutter.common.api.BusinessIds;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.internal.LocalImageAssets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real row locks, FK protection and rollback, using only the disposable
 * integration DB.
 */
class ImageAssetCleanupIT {
    @TempDir
    Path directory;
    JdbcTemplate db;
    DataSourceTransactionManager manager;
    final List<String> assets = new ArrayList<>();
    final long copy = BusinessIds.next(), preview = BusinessIds.next(), history = BusinessIds.next();
    @BeforeEach
    void setup() {
        String url = System.getenv("MOLEBUTTER_TEST_DB_URL");
        Assumptions.assumeTrue(url != null && url.contains("/molebutter_test?"));
        Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").load().migrate();
        var source = new DriverManagerDataSource(url, "test_app", "isolated-test-app-password");
        db = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        db.update(
                "INSERT INTO coupang_product_working_copy(id,revision,product_name,document_json,account_key,changes_json,created_by,updated_by,created_at,updated_at) VALUES(?,0,'cleanup test','{}',REPEAT('a',64),'[]',1,1,NOW(6),NOW(6))",
                copy);
        db.update(
                "INSERT INTO coupang_submission_preview(id,working_copy_id,working_revision,created_by,requested,document_json,created_at,expires_at) VALUES(?,?,0,1,FALSE,'{}',NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY))",
                preview, copy);
        db.update(
                "INSERT INTO legacy_coupang_listing_history(id,working_copy_id,account_key,listing_version,mapping_json,document_json,request_hash,request_id,created_by,created_at) VALUES(?,?,REPEAT('a',64),1,'{}','{}',REPEAT('b',64),?,1,NOW(6))",
                history, copy, UUID.randomUUID().toString());
    }
    @AfterEach
    void cleanup() {
        if (db == null)
            return;
        db.update("DELETE FROM coupang_working_copy_asset WHERE working_copy_id=?", copy);
        db.update("DELETE FROM coupang_execution_asset WHERE submission_id=?", preview);
        db.update("DELETE FROM legacy_coupang_listing_history_asset WHERE history_id=?", history);
        for (String id : assets) {
            db.update("DELETE FROM legacy_coupang_asset_pin WHERE asset_id=?", id);
            db.update("DELETE FROM coupang_image_edit WHERE asset_id=? OR original_asset_id=?", id, id);
        }
        for (String id : assets)
            db.update("DELETE FROM coupang_asset WHERE id=?", id);
        db.update("DELETE FROM legacy_coupang_listing_history WHERE id=?", history);
        db.update("DELETE FROM coupang_submission_preview WHERE id=?", preview);
        db.update("DELETE FROM coupang_product_working_copy WHERE id=?", copy);
    }
    String asset() throws Exception {
        String id = UUID.randomUUID().toString();
        assets.add(id);
        Files.writeString(directory.resolve(id + ".png"), "isolated image " + id);
        db.update(
                "INSERT INTO coupang_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at,pending_delete_at) VALUES(?,?,'image/png',500,500,100,1,NOW(6),DATE_SUB(NOW(6),INTERVAL 2 DAY))",
                id, id + ".png");
        return id;
    }
    LocalImageAssets collector(PlatformTransactionManager transactions) {
        return new LocalImageAssets(db, mock(BusinessAccess.class), transactions, directory.toString(), "");
    }
    void retained(String id) {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_asset WHERE id=?", Long.class, id)).isEqualTo(1);
        assertThat(Files.exists(directory.resolve(id + ".png"))).isTrue();
    }
    @Test
    void currentAuditAndEditReferencesProtectImagesAndPublicationAloneDoesNot() throws Exception {
        String working = asset(), execution = asset(), audit = asset(), pin = asset(), original = asset(),
                edited = asset(), free = asset();
        db.update("INSERT INTO coupang_working_copy_asset VALUES(?,?)", copy, working);
        db.update("INSERT INTO coupang_execution_asset VALUES(?,?)", preview, execution);
        db.update("INSERT INTO legacy_coupang_listing_history_asset VALUES(?,?)", history, audit);
        db.update("INSERT INTO legacy_coupang_asset_pin VALUES(?)", pin);
        db.update("UPDATE coupang_asset SET pending_delete_at=NULL WHERE id=?", edited);
        db.update(
                "INSERT INTO coupang_image_edit VALUES(?,?,'https://images.example.test/original.png',REPEAT('c',64),0,0,500,500,1)",
                edited, original);
        db.update("INSERT INTO coupang_asset_publication VALUES(?,REPEAT('d',64),NOW(6))", free);
        collector(manager).reapUnreferenced();
        for (String id : List.of(working, execution, audit, pin, original, edited))
            retained(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_asset WHERE id=?", Long.class, free)).isZero();
        assertThat(
                db.queryForObject("SELECT COUNT(*) FROM coupang_asset_publication WHERE asset_id=?", Long.class, free))
                .isZero();
        assertThat(Files.exists(directory.resolve(free + ".png"))).isFalse();
    }
    @Test
    void aPinCommittedAfterCandidateSelectionProtectsTheLockedAsset() throws Exception {
        String id = asset();
        var selected = new java.util.concurrent.CountDownLatch(1);
        var observed = spy(db);
        doAnswer(i -> {
            var result = i.callRealMethod();
            selected.countDown();
            return result;
        }).when(observed).queryForList(contains("SELECT id FROM coupang_asset a WHERE"), eq(String.class));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending = new org.springframework.transaction.support.TransactionTemplate(manager).execute(tx -> {
                db.queryForList("SELECT id FROM coupang_asset WHERE id=? FOR UPDATE", String.class, id);
                var task = executor.submit(() -> new LocalImageAssets(observed, mock(BusinessAccess.class), manager,
                        directory.toString(), "").reapUnreferenced());
                try {
                    assertThat(selected.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                db.update("INSERT INTO legacy_coupang_asset_pin VALUES(?)", id);
                return task;
            });
            pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
            retained(id);
        }
    }
    @Test
    void anUncertainUploadCommitIsResolvedWithoutDeletingCommittedReferences() throws Exception {
        for (boolean committed : List.of(true, false)) {
            var uncertain = new DataSourceTransactionManager(db.getDataSource()) {
                @Override
                protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                    if (committed)
                        super.doCommit(status);
                    else
                        super.doRollback(status);
                    throw new TransactionSystemException("synthetic lost commit acknowledgement");
                }
            };
            var uploaded = new java.util.concurrent.atomic.AtomicReference<String>();
            var images = new LocalImageAssets(db, mock(BusinessAccess.class), uncertain, directory.toString(), "");
            assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(uncertain)
                    .executeWithoutResult(tx -> {
                        String id = images.upload(1L, imageBytes()).id();
                        assets.add(id);
                        uploaded.set(id);
                        db.update("INSERT INTO legacy_coupang_asset_pin VALUES(?)", id);
                    })).isInstanceOf(TransactionSystemException.class);
            String id = uploaded.get();
            assertThat(Files.exists(directory.resolve(id + ".png"))).isTrue();
            assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isTrue();
            collector(manager).reapUnreferenced();
            if (committed)
                retained(id);
            else {
                assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_asset WHERE id=?", Long.class, id)).isZero();
                assertThat(Files.exists(directory.resolve(id + ".png"))).isFalse();
            }
            assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isFalse();
        }
    }
    byte[] imageBytes() {
        try {
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(
                    new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
    @Test
    void aKnownUploadRollbackRemovesOnlyItsUncommittedFile() {
        var images = new LocalImageAssets(db, mock(BusinessAccess.class), manager, directory.toString(), "");
        var uploaded = new org.springframework.transaction.support.TransactionTemplate(manager).execute(tx -> {
            var image = images.upload(1L, imageBytes());
            assets.add(image.id());
            tx.setRollbackOnly();
            return image;
        });
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_asset WHERE id=?", Long.class, uploaded.id()))
                .isZero();
        assertThat(Files.exists(directory.resolve(uploaded.id() + ".png"))).isFalse();
    }
    @Test
    void aFailedRollbackFileDeleteIsRecoveredAfterRestart() throws Exception {
        var images = new LocalImageAssets(db, mock(BusinessAccess.class), manager, directory.toString(), "");
        var uploaded = new org.springframework.transaction.support.TransactionTemplate(manager).execute(tx -> {
            var image = images.upload(1L, imageBytes());
            assets.add(image.id());
            var path = directory.resolve(image.id() + ".png");
            try {
                Files.delete(path);
                Files.createDirectory(path);
                Files.writeString(path.resolve("locked"), "synthetic deletion failure");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            tx.setRollbackOnly();
            return image;
        });
        String id = uploaded.id();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_asset WHERE id=?", Long.class, id)).isZero();
        assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isTrue();
        var path = directory.resolve(id + ".png");
        Files.delete(path.resolve("locked"));
        Files.delete(path);
        Files.write(path, imageBytes());
        collector(manager).reapUnreferenced();
        assertThat(Files.exists(path)).isFalse();
        assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isFalse();
    }
    @Test
    void stoppingTheProcessOnEitherSideOfCommitPreservesRecovery() throws Exception {
        for (String boundary : List.of("before", "after")) {
            String id = asset();
            var output = directory.resolve("stopped-" + boundary + ".log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    StoppedCollector.class.getName(), directory.toString(), boundary).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            try {
                assertThat(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
                        .as("cleanup subprocess terminates at %s commit", boundary).isTrue();
                assertThat(process.exitValue()).as(Files.readString(output)).isZero();
            } finally {
                if (process.isAlive())
                    process.destroyForcibly();
            }
            assertThat(Files.exists(directory.resolve(id + ".png"))).isTrue();
            assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isTrue();
            if (boundary.equals("before")) {
                retained(id);
                db.update("INSERT INTO legacy_coupang_asset_pin VALUES(?)", id);
            } else {
                assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_asset WHERE id=?", Long.class, id)).isZero();
            }
            collector(manager).reapUnreferenced();
            if (boundary.equals("before"))
                retained(id);
            else
                assertThat(Files.exists(directory.resolve(id + ".png"))).isFalse();
            assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isFalse();
        }
    }
    public static final class StoppedCollector {
        public static void main(String[] args) {
            String url = System.getenv("MOLEBUTTER_TEST_DB_URL");
            if (url == null || !url.contains("/molebutter_test?"))
                throw new IllegalStateException("Isolated DB required");
            var db = new JdbcTemplate(new DriverManagerDataSource(url, "test_app", "isolated-test-app-password"));
            var delegate = new DataSourceTransactionManager(db.getDataSource());
            var boundary = new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return delegate.getTransaction(definition);
                }
                public void rollback(TransactionStatus status) {
                    delegate.rollback(status);
                }
                public void commit(TransactionStatus status) {
                    if (args[1].equals("after"))
                        delegate.commit(status);
                    Runtime.getRuntime().halt(0);
                }
            };
            new LocalImageAssets(db, null, boundary, args[0], "").reapUnreferenced();
            throw new IllegalStateException("Expected to halt at commit");
        }
    }
    @Test
    void aRolledBackCommitAndRestartPreserveTheImageAndItsNewAuditPin() throws Exception {
        String id = asset();
        var failedCommit = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }
            public void rollback(TransactionStatus status) {
                manager.rollback(status);
            }
            public void commit(TransactionStatus status) {
                manager.rollback(status);
                throw new TransactionSystemException("synthetic commit failure after rollback");
            }
        };
        collector(failedCommit).reapUnreferenced();
        retained(id);
        assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isTrue();
        db.update("INSERT INTO legacy_coupang_asset_pin VALUES(?)", id);
        collector(manager).reapUnreferenced();
        retained(id);
        assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isFalse();
    }
}
