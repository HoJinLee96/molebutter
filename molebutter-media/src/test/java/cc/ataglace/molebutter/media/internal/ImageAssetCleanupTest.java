package cc.ataglace.molebutter.media.internal;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ImageAssetCleanupTest {
    @TempDir
    Path directory;
    final String id = "11111111-1111-4111-8111-111111111111";
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    LocalImageAssets collector() {
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(db.queryForList(contains("SELECT id FROM coupang_asset"), eq(String.class))).thenReturn(List.of(id));
        when(db.queryForList(contains("SELECT relative_path"), eq(String.class), eq(id)))
                .thenReturn(List.of(id + ".png"));
        when(db.queryForObject(anyString(), eq(Long.class), eq(id))).thenReturn(0L);
        when(db.update(eq("DELETE FROM coupang_asset WHERE id=?"), eq(id))).thenReturn(1);
        return new LocalImageAssets(db, mock(BusinessAccess.class), manager, directory.toString(), "");
    }
    @Test
    void aRejectedDatabaseDeleteNeverDeletesTheFile() throws Exception {
        var collector = collector();
        var file = Files.writeString(directory.resolve(id + ".png"), "retained image");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic FK")).when(db)
                .update(eq("DELETE FROM coupang_asset WHERE id=?"), eq(id));
        assertThatCode(collector::reapUnreferenced).doesNotThrowAnyException();
        assertThat(Files.readString(file)).isEqualTo("retained image");
    }
    @Test
    void referencesAddedAfterCandidateSelectionProtectTheFile() throws Exception {
        for (String table : List.of("coupang_working_copy_asset", "coupang_execution_asset",
                "legacy_coupang_listing_history_asset", "legacy_coupang_asset_pin", "coupang_image_edit")) {
            var collector = collector();
            var file = Files.writeString(directory.resolve(id + ".png"), "protected image");
            when(db.queryForObject(contains("FROM " + table + " "), eq(Long.class), eq(id))).thenReturn(1L);
            collector.reapUnreferenced();
            assertThat(Files.exists(file)).isTrue();
            verify(db, never()).update(eq("DELETE FROM coupang_asset WHERE id=?"), eq(id));
            reset(db);
        }
    }
    @Test
    void aFailedCommitNeverDeletesTheFile() throws Exception {
        var collector = collector();
        var file = Files.writeString(directory.resolve(id + ".png"), "retained after failed commit");
        doThrow(new org.springframework.transaction.TransactionSystemException("synthetic commit failure"))
                .when(manager).commit(any());
        collector.reapUnreferenced();
        assertThat(Files.readString(file)).isEqualTo("retained after failed commit");
        assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isTrue();
    }
    @Test
    void aFileDeletionFailureIsRetriedEvenAfterRestart() throws Exception {
        var collector = collector();
        var path = Files.createDirectory(directory.resolve(id + ".png"));
        Files.writeString(path.resolve("locked"), "synthetic nonempty directory");
        collector.reapUnreferenced();
        assertThat(Files.exists(path)).isTrue();
        assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isTrue();
        Files.delete(path.resolve("locked"));
        Files.delete(path);
        Files.writeString(path, "now removable");
        when(db.queryForList(contains("SELECT id FROM coupang_asset"), eq(String.class))).thenReturn(List.of());
        new LocalImageAssets(db, mock(BusinessAccess.class), manager, directory.toString(), "").reapUnreferenced();
        assertThat(Files.exists(path)).isFalse();
        assertThat(Files.exists(directory.resolve(id + ".png.delete-pending"))).isFalse();
    }
    @Test
    void aRolledBackDeleteKeepsItsFileWhenRecoveringTheMarker() throws Exception {
        var collector = collector();
        var path = Files.writeString(directory.resolve(id + ".png"), "rolled back image");
        var marker = Files.createFile(directory.resolve(id + ".png.delete-pending"));
        when(db.queryForList(contains("SELECT id FROM coupang_asset"), eq(String.class))).thenReturn(List.of());
        when(db.queryForList(eq("SELECT id FROM coupang_asset WHERE id=? FOR UPDATE"), eq(String.class), eq(id)))
                .thenReturn(List.of(id));
        collector.reapUnreferenced();
        assertThat(Files.readString(path)).isEqualTo("rolled back image");
        assertThat(Files.exists(marker)).isFalse();
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test
    void deletionWaitsUntilItsOwnTransactionHasCommitted() throws Exception {
        var collector = collector();
        var path = Files.writeString(directory.resolve(id + ".png"), "committing image");
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i -> {
            if (first.getAndSet(false))
                assertThat(Files.exists(path)).isTrue();
            return null;
        }).when(manager).commit(any());
        collector.reapUnreferenced();
        assertThat(Files.exists(path)).isFalse();
        verify(manager, atLeastOnce()).getTransaction(argThat(d -> d
                .getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }
    @Test
    void permanentlyFailingMarkersDoNotStarveLaterRecoverableFiles() throws Exception {
        var collector = collector();
        when(db.queryForList(contains("SELECT id FROM coupang_asset"), eq(String.class))).thenReturn(List.of());
        for (int i = 0; i < 101; i++) {
            String key = java.util.UUID.randomUUID().toString();
            Files.writeString(directory.resolve(key + ".png"), "pending image");
            Files.createFile(directory.resolve(key + ".png.delete-pending"));
        }
        List<Path> markers;
        try (var listing = Files.newDirectoryStream(directory, "*.delete-pending")) {
            markers = java.util.stream.StreamSupport.stream(listing.spliterator(), false).toList();
        }
        Path recoverable = directory.resolve(markers.getLast().getFileName().toString().replace(".delete-pending", ""));
        for (var marker : markers.subList(0, 100)) {
            var file = directory.resolve(marker.getFileName().toString().replace(".delete-pending", ""));
            Files.delete(file);
            Files.createDirectory(file);
            Files.writeString(file.resolve("locked"), "permanent synthetic failure");
        }
        for (var marker : markers)
            Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.fromMillis(1));
        collector.reapUnreferenced();
        new LocalImageAssets(db, mock(BusinessAccess.class), manager, directory.toString(), "").reapUnreferenced();
        assertThat(Files.exists(recoverable)).isFalse();
    }
    @Test
    void aFailedCandidateDoesNotBlockTheRemainingBatch() throws Exception {
        var collector = collector();
        String other = "22222222-2222-4222-8222-222222222222";
        var first = Files.writeString(directory.resolve(id + ".png"), "protected");
        var second = Files.writeString(directory.resolve(other + ".png"), "unreferenced");
        when(db.queryForList(contains("SELECT id FROM coupang_asset"), eq(String.class)))
                .thenReturn(List.of(id, other));
        when(db.queryForList(contains("SELECT relative_path"), eq(String.class), eq(other)))
                .thenReturn(List.of(other + ".png"));
        when(db.queryForObject(anyString(), eq(Long.class), eq(other))).thenReturn(0L);
        when(db.update(eq("DELETE FROM coupang_asset WHERE id=?"), eq(other))).thenReturn(1);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic FK")).when(db)
                .update(eq("DELETE FROM coupang_asset WHERE id=?"), eq(id));
        collector.reapUnreferenced();
        assertThat(Files.exists(first)).isTrue();
        assertThat(Files.exists(second)).isFalse();
    }
}
