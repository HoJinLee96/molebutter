package cc.ataglace.molebutter.app.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.storage.api.*;
import java.net.URI;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductImageUploadServiceTest {
    private final ProductImageWorkspace workspace = mock(ProductImageWorkspace.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final List<Runnable> queue = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private ProductImageUploadService uploads;

    @BeforeEach void setup() {
        uploads = new ProductImageUploadService(workspace, storage, queue::add, 32 * 1024 * 1024L, clock);
        when(storage.publicUrl(eq(StorageArea.PUBLIC), anyString())).thenAnswer(call ->
                Optional.of(URI.create("https://assets.example.test/" + call.<String>getArgument(1))));
        when(storage.head(eq(StorageArea.PUBLIC), anyString())).thenReturn(Optional.empty());
        when(storage.head(eq(StorageArea.PRIVATE), anyString())).thenReturn(Optional.empty());
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> images(2));
        when(storage.create(eq(StorageArea.PUBLIC), anyString(), any(), anyString(), anyMap())).thenReturn(
                new ObjectMetadata(StorageArea.PUBLIC, "test/key.png", 3, "image/png", "\"etag\"", null, Map.of()));
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private ProductImageUploadRequest request(String id, int count) {
        List<DownloadImageItemDto> items = new ArrayList<>();
        for (int i = 0; i < count; i++) items.add(new DownloadImageItemDto(i, null, "https://nimg.lfmall.co.kr/" + i + ".jpg"));
        return new ProductImageUploadRequest(id, "HIHO6F861W2", "HAZZYS", "HIHO861W2", items);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static List<ExportImageDto> images(int count) {
        List<ExportImageDto> images = new ArrayList<>();
        for (int i = 0; i < count; i++) images.add(new ExportImageDto(i == 0 ? "01.jpg" : i == 1 ? "사이즈.png" : i == 2 ? "상품정보.png" : "%02d.png".formatted(i + 1),
                i == 0 ? "image/jpeg" : "image/png", i == 0 ? ".jpg" : ".png", new byte[]{(byte) (i + 1), 2, 3},
                i == 1 || i == 2 ? ExportImageCategory.PROCESSED : ExportImageCategory.OFFICIAL));
        return images;
    }
    private static String key(String id, int sequence, String suffix) {
        String name = sequence == 2 ? "사이즈.png" : sequence == 3 ? "상품정보.png" : "%02d%s".formatted(sequence, suffix);
        return key(id, name);
    }
    private static String key(String id, String fileName) {
        String directory = fileName.startsWith("사이즈") || fileName.startsWith("상품정보") ? "processed" : "official";
        return "products/HIHO861W2/" + directory + "/" + fileName;
    }

    @Test void uploadsActual750PixelSizeRenditionAndHashesTheTransferredBytes() throws Exception {
        var source=new java.awt.image.BufferedImage(2880,1880,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=source.createGraphics();g.setColor(java.awt.Color.BLUE);g.fillRect(0,0,2880,1880);g.dispose();
        byte[] original=cc.ataglace.molebutter.imaging.internal.render.RenderSupport.encodePng(source);
        byte[] prepared=cc.ataglace.molebutter.imaging.internal.render.SizeGuideUploadImage.prepare(original);
        when(workspace.prepareExport(anyLong(),any())).thenReturn(()->List.of(
                new ExportImageDto("사이즈.png","image/png",".png",prepared,ExportImageCategory.PROCESSED)));
        String id=id();uploads.start(42L,request(id,1));queue.getFirst().run();
        assertThat(uploads.get(42L,id).status()).isEqualTo("SUCCEEDED");
        var bytes=ArgumentCaptor.forClass(byte[].class);
        verify(storage).create(eq(StorageArea.PUBLIC),eq(key(id,"사이즈.png")),bytes.capture(),eq("image/png"),
                argThat(m->m.get("sha256").equals(java.util.HexFormat.of().formatHex(sha256(prepared)))));
        var written=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes.getValue()));
        assertThat(written.getWidth()).isEqualTo(750);assertThat(written.getHeight()).isEqualTo(490);
        assertThat(source.getWidth()).isEqualTo(2880);
    }
    @Test void normalizesDestinationAndUploadsMixedOrderOnceWithoutSourceCodeMutation() {
        String id = id();
        var request = request(id, 3);
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> images(3));
        var spaced = new ProductImageUploadRequest(id.toUpperCase(Locale.ROOT), " hiho6f861w2 ", " hazzys ", " hiho861w2 ", request.images());
        var accepted = uploads.start(42L, spaced);
        assertThat(accepted.id()).isEqualTo(id);
        assertThat(accepted.status()).isEqualTo("RUNNING");
        assertThat(accepted.result().uploadProductCode()).isEqualTo("HIHO861W2");
        verify(storage, never()).create(any(), any(), any(), any(), any());
        var selection = ArgumentCaptor.forClass(DownloadRequestDto.class);
        verify(workspace).prepareExport(eq(42L), selection.capture());
        assertThat(selection.getValue().productCode()).isEqualTo("HIHO6F861W2");
        assertThat(selection.getValue().images()).containsExactlyElementsOf(request.images());
        queue.getFirst().run();
        var order = inOrder(storage);
        order.verify(storage).create(eq(StorageArea.PUBLIC), eq(key(id, 1, ".jpg")), eq(new byte[]{1, 2, 3}), eq("image/jpeg"), argThat(m -> m.get("sha256").length() == 64));
        order.verify(storage).create(eq(StorageArea.PUBLIC), eq(key(id, 2, ".png")), eq(new byte[]{2, 2, 3}), eq("image/png"), argThat(m -> m.get("sha256").length() == 64));
        order.verify(storage).create(eq(StorageArea.PUBLIC), eq(key(id, 3, ".png")), eq(new byte[]{3, 2, 3}), eq("image/png"), argThat(m -> m.get("sha256").length() == 64));
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::fileName).containsExactly("01.jpg", "사이즈.png", "상품정보.png");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::key).containsExactly(key(id, 1, ".jpg"), key(id, 2, ".png"), key(id, 3, ".png"));
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::key).containsExactly(
                "products/HIHO861W2/official/01.jpg", "products/HIHO861W2/processed/사이즈.png",
                "products/HIHO861W2/processed/상품정보.png");
        assertThat(result.result().files().getFirst().url()).isEqualTo("https://assets.example.test/" + key(id, 1, ".jpg"));
        assertThat(uploads.start(42L, request)).isEqualTo(result);
        assertThat(queue).hasSize(1);
        verify(workspace, times(1)).prepareExport(anyLong(), any());
        verify(storage, times(3)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void recentCompletedLinkageRetainsSelectionsWithoutReadingOrWritingStorage(){
        String job=id();var input=request(job,2);uploads.start(42L,input);queue.getFirst().run();
        clearInvocations(storage);var link=uploads.recent(42L,input.productCode(),input.brandCode(),input.uploadProductCode()).orElseThrow();
        assertThat(link).hasSize(2);
        assertThat(uploads.matchesRecent(42L,input.productCode(),input.brandCode(),input.uploadProductCode(),link.get(input.images().getFirst()).key(),images(2).getFirst().bytes())).isTrue();
        assertThat(uploads.matchesRecent(42L,input.productCode(),input.brandCode(),input.uploadProductCode(),link.get(input.images().getFirst()).key(),new byte[]{99})).isFalse();assertThat(link.get(input.images().getFirst()).key()).endsWith("official/01.jpg");
        assertThat(uploads.recent(43L,input.productCode(),input.brandCode(),input.uploadProductCode())).isEmpty();
        assertThat(uploads.recent(42L,"OTHER",input.brandCode(),input.uploadProductCode())).isEmpty();verifyNoInteractions(storage);
    }
    @Test void duplicateSizeAndNoticeExportsKeepFriendlyNamesAsActualObjectBasenames() {
        String id = id();
        var images = List.of(images(1).getFirst(),
                new ExportImageDto("사이즈.png", "image/png", ".png", new byte[]{2}, ExportImageCategory.PROCESSED),
                new ExportImageDto("상품정보.png", "image/png", ".png", new byte[]{3}, ExportImageCategory.PROCESSED),
                new ExportImageDto("사이즈_2.png", "image/png", ".png", new byte[]{4}, ExportImageCategory.PROCESSED),
                new ExportImageDto("상품정보_2.png", "image/png", ".png", new byte[]{5}, ExportImageCategory.PROCESSED));
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> images);
        uploads.start(42L, request(id, 5)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::fileName)
                .containsExactly("01.jpg", "사이즈.png", "상품정보.png", "사이즈_2.png", "상품정보_2.png");
        for (var file : result.result().files()) {
            assertThat(file.key()).isEqualTo(key(id, file.fileName()));
            verify(storage).create(eq(StorageArea.PUBLIC), eq(key(id, file.fileName())), any(), any(), any());
        }
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::key).doesNotHaveDuplicates();
    }

    @Test void unsafeOrDuplicateExportNamesAreRejectedBeforeAnyObjectWrite() {
        for (String name : List.of("../사이즈.png", "other/상품정보.png", "official/01.png", "processed/상품정보.png", "사이즈.png")) {
            var prepared = List.of(new ExportImageDto("사이즈.png", "image/png", ".png", new byte[]{1}, ExportImageCategory.PROCESSED),
                    new ExportImageDto(name, "image/png", ".png", new byte[]{2}, ExportImageCategory.PROCESSED));
            when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> prepared);
            String id = id(); uploads.start(42L, request(id, 2)); queue.getLast().run();
            assertThat(uploads.get(42L, id).status()).isEqualTo("FAILED");
        }
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void serverCategorySeparatesOfficialPngAndGeneratedPngWithoutChangingBasenames() {
        String id = id();
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> List.of(
                new ExportImageDto("01.png", "image/png", ".png", new byte[]{1}, ExportImageCategory.OFFICIAL),
                new ExportImageDto("사이즈.png", "image/png", ".png", new byte[]{2}, ExportImageCategory.PROCESSED),
                new ExportImageDto("03.jpg", "image/jpeg", ".jpg", new byte[]{3}, ExportImageCategory.OFFICIAL)));
        uploads.start(42L, request(id, 3)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::fileName)
                .containsExactly("01.png", "사이즈.png", "03.jpg");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::key).containsExactly(
                "products/HIHO861W2/official/01.png", "products/HIHO861W2/processed/사이즈.png",
                "products/HIHO861W2/official/03.jpg");
    }

    @Test void missingServerCategoryIsRejectedBeforeAnyObjectWrite() {
        var unclassified = new ExportImageDto("02.png", "image/png", ".png", new byte[]{1}, null);
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> List.of(images(1).getFirst(), unclassified));
        String id = id(); uploads.start(42L, request(id, 2)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("파일명");
        assertThat(result.result().files()).isEmpty();
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void disabledConfigurationFailsBeforePreparingOrDownloadingAnything() {
        when(storage.publicUrl(any(), any())).thenThrow(new StorageException(StorageException.Code.NOT_CONFIGURED));
        assertThatThrownBy(() -> uploads.start(42L, request(id(), 2)))
                .isInstanceOf(ProductImageUploadFailure.class).hasMessageContaining("R2").hasMessageContaining("활성화");
        verifyNoInteractions(workspace);
        verify(storage, never()).create(any(), any(), any(), any(), any());
        assertThat(queue).isEmpty();
    }

    @Test void changedPayloadWithTheSameIdIsRejectedAndAnotherActorCannotReadOrReuseIt() {
        String id = id(); var request = request(id, 2); uploads.start(42L, request);
        var changed = new ProductImageUploadRequest(id, request.productCode(), request.brandCode(), "OTHER123", request.images());
        assertThatThrownBy(() -> uploads.start(42L, changed)).hasMessageContaining("내용이 변경");
        assertThatThrownBy(() -> uploads.start(43L, request)).hasMessageContaining("찾을 수 없습니다");
        assertThatThrownBy(() -> uploads.get(43L, id)).hasMessageContaining("찾을 수 없습니다");
        assertThat(queue).hasSize(1);
    }

    @Test void rejectsPathTraversalUnsafeCodesMissingSourceAndMalformedGeneratedIdsBeforeAnyStorageOperation() {
        String id = id(); var request = request(id, 2);
        for (String code : List.of("../HIHO861W2", "a/bc", "가방123", "a".repeat(41), "ABC")) {
            assertThatThrownBy(() -> uploads.start(42L, new ProductImageUploadRequest(id, request.productCode(), "HAZZYS", code, request.images())))
                    .isInstanceOf(ProductImageUploadFailure.class);
        }
        assertThatThrownBy(() -> uploads.start(42L, request("not-a-uuid", 2))).isInstanceOf(ProductImageUploadFailure.class);
        assertThatThrownBy(() -> uploads.start(42L, new ProductImageUploadRequest(id, request.productCode(), "HAZZYS", request.uploadProductCode(),
                List.of(new DownloadImageItemDto(0, null))))).hasMessageContaining("원본 이미지 주소");
        assertThatThrownBy(() -> uploads.start(42L, new ProductImageUploadRequest(id, request.productCode(), "HAZZYS", request.uploadProductCode(),
                List.of(new DownloadImageItemDto(null, "------------------------------------"))))).hasMessageContaining("요청 ID");
        assertThatThrownBy(() -> uploads.start(42L, new ProductImageUploadRequest(id, request.productCode(), "HAZZYS", request.uploadProductCode(), List.of())))
                .hasMessageContaining("1개 이상");
        verifyNoInteractions(workspace, storage);
    }

    @Test void validatesEveryPreparedImageBeforeFirstWriteAndKeepsPreparationFailureSafe() {
        String id = id();
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> List.of(images(1).getFirst(),
                new ExportImageDto("second.png", "image/png", "../png", new byte[]{1}, ExportImageCategory.OFFICIAL)));
        uploads.start(42L, request(id, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, id).status()).isEqualTo("FAILED");
        assertThat(uploads.get(42L, id).result().files()).isEmpty();
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void perFileLimitFailsBeforeAnyWriteEvenWhenAnEarlierFileWouldFit() {
        uploads = new ProductImageUploadService(workspace, storage, queue::add, 2, clock);
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> List.of(new ExportImageDto("01.png", "image/png", ".png", new byte[]{1}, ExportImageCategory.OFFICIAL),
                new ExportImageDto("02.png", "image/png", ".png", new byte[]{1, 2, 3}, ExportImageCategory.OFFICIAL)));
        String id = id(); uploads.start(42L, request(id, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, id).status()).isEqualTo("FAILED");
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void preparationFailureDoesNotExposeProviderDetailsOrWritePartialFiles() {
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> { throw new IllegalStateException("secret provider body"); });
        String id = id(); uploads.start(42L, request(id, 2)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).doesNotContain("secret");
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void definiteFailureStopsRemainingWritesAndRetainsOnlyConfirmedFiles() {
        String id = id();
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> images(3));
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(id, 2, ".png")), any(), anyString(), anyMap()))
                .thenThrow(new StorageException(StorageException.Code.ACCESS_DENIED));
        uploads.start(42L, request(id, 3)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("권한");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::key).containsExactly(key(id, 1, ".jpg"));
        assertThat(uploads.start(42L, request(id, 3))).isEqualTo(result);
        verify(storage, times(2)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
        verify(storage, never()).create(any(), eq(key(id, 3, ".png")), any(), any(), any());
    }

    @Test void responseLossDoesNotRepeatWritesAndEmptyHeadRemainsUnknown() {
        String id = id();
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(id, 2, ".png")), any(), anyString(), anyMap()))
                .thenThrow(new StorageException(StorageException.Code.WRITE_UNCERTAIN));
        when(storage.head(eq(StorageArea.PUBLIC), anyString())).thenReturn(Optional.empty());
        uploads.start(42L, request(id, 2)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.result().files()).hasSize(1);
        assertThat(uploads.start(42L, request(id, 2)).status()).isEqualTo("UNKNOWN");
        assertThat(uploads.get(42L, id).status()).isEqualTo("UNKNOWN");
        verify(storage, times(2)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void lostFinalResponseIsConfirmedByHeadHashWithoutAnotherWrite() throws Exception {
        String id = id();
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(id, 2, ".png")), any(), anyString(), anyMap()))
                .thenThrow(new StorageException(StorageException.Code.WRITE_UNCERTAIN));
        when(storage.head(StorageArea.PUBLIC, key(id, 2, ".png"))).thenReturn(Optional.empty(), Optional.of(confirmed(id, 2)));
        uploads.start(42L, request(id, 2)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).hasSize(2);
        assertThat(result.error()).isNull();
        assertThat(uploads.start(42L, request(id, 2))).isEqualTo(result);
        verify(storage, times(2)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
        verify(storage, times(3)).head(eq(StorageArea.PUBLIC), anyString());
    }

    @Test void confirmedUncertainFileWithUnexecutedLaterFilesEndsAsPartialFailureWithoutResuming() throws Exception {
        String id = id();
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> images(3));
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(id, 2, ".png")), any(), anyString(), anyMap()))
                .thenThrow(new StorageException(StorageException.Code.WRITE_UNCERTAIN));
        when(storage.head(StorageArea.PUBLIC, key(id, 2, ".png"))).thenReturn(Optional.empty(), Optional.of(confirmed(id, 2)));
        uploads.start(42L, request(id, 3)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.result().files()).hasSize(2);
        assertThat(result.error()).contains("나머지 파일은 전송하지");
        verify(storage, times(2)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void providerReadFailureAndMismatchingExistingObjectDoNotClearUnknown() {
        String id = id();
        when(storage.create(eq(StorageArea.PUBLIC), any(), any(), any(), any())).thenThrow(new StorageException(StorageException.Code.CONFLICT));
        when(storage.head(eq(StorageArea.PUBLIC), eq(key(id, 1, ".jpg")))).thenReturn(Optional.empty())
                .thenThrow(new IllegalStateException("provider secret"))
                .thenReturn(Optional.of(new ObjectMetadata(StorageArea.PUBLIC, key(id, 1, ".jpg"), 3, "image/jpeg", "etag", null, Map.of("sha256", "different"))));
        uploads.start(42L, request(id, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, id).status()).isEqualTo("UNKNOWN");
        assertThat(uploads.get(42L, id).status()).isEqualTo("UNKNOWN");
        assertThat(uploads.get(42L, id).error()).doesNotContain("secret");
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void globalAndActorLimitsRejectNewJobsButPermitAnExistingIdLookup() {
        String first = id(); uploads.start(42L, request(first, 2));
        assertThat(uploads.start(42L, request(first, 2)).id()).isEqualTo(first);
        assertThatThrownBy(() -> uploads.start(42L, request(id(), 2))).hasMessageContaining("진행 중");
        uploads.start(43L, destination(request(id(), 2), "OTHER123"));
        assertThatThrownBy(() -> uploads.start(44L, destination(request(id(), 2), "OTHER124"))).hasMessageContaining("진행 중");
        assertThat(queue).hasSize(2);
    }

    @Test void executorRejectionRemovesOnlyUnstartedRecordSoTheSameIdCanBeAcceptedLater() {
        var reject = new ProductImageUploadService(workspace, storage, work -> { throw new RejectedExecutionException(); }, 100, clock);
        String id = id();
        assertThatThrownBy(() -> reject.start(42L, request(id, 2))).hasMessageContaining("대기열");
        assertThatThrownBy(() -> reject.get(42L, id)).hasMessageContaining("찾을 수 없습니다");
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void unexpiredFinishedIdsAreRetainedAndExpiredReplaySkipsStoredFile() {
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> images(1));
        ProductImageUploadRequest first = null;
        for (int i = 0; i < 32; i++) {
            var next = destination(request(id(), 1), "TEST%04d".formatted(i));
            if (i == 0) first = next;
            uploads.start(42L, next); queue.getLast().run();
        }
        var original = first;
        assertThatThrownBy(() -> uploads.start(42L, request(id(), 1))).hasMessageContaining("보관 공간");
        assertThat(uploads.start(42L, original).status()).isEqualTo("SUCCEEDED");
        clock.advance(Duration.ofHours(4));
        assertThatThrownBy(() -> uploads.get(42L, original.requestId())).hasMessageContaining("찾을 수 없습니다");
        when(storage.head(StorageArea.PUBLIC, "products/TEST0000/official/01.jpg"))
                .thenReturn(Optional.of(stored("products/TEST0000/official/01.jpg")));
        uploads.start(42L, original); queue.getLast().run();
        assertThat(uploads.get(42L, original.requestId()).result().skippedFiles()).hasSize(1);
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), eq("products/TEST0000/official/01.jpg"), any(), any(), any());
    }

    @Test void skipsOnlyExactExistingKeysAndUploadsMissingSizeImage() {
        String id = id();
        when(storage.head(StorageArea.PUBLIC, key(id, 1, ".jpg"))).thenReturn(Optional.of(stored(key(id, 1, ".jpg"))));
        uploads.start(42L, request(id, 2)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::fileName).containsExactly("사이즈.png");
        assertThat(result.result().skippedFiles()).extracting(ProductImageUploadJob.SkippedFile::key).containsExactly(key(id, 1, ".jpg"));
        assertThat(result.result().skippedFiles().getFirst().reason()).isEqualTo("EXISTS");
        verify(storage, never()).create(eq(StorageArea.PUBLIC), eq(key(id, 1, ".jpg")), any(), any(), any());
        verify(storage).create(eq(StorageArea.PUBLIC), eq(key(id, 2, ".png")), any(), any(), any());
        verify(storage, never()).list(any(), any(), any(), anyInt());
    }

    @Test void readFailureOnAnyDestinationStopsBeforeAllPublicWrites() {
        when(storage.head(StorageArea.PUBLIC, key("", 2, ".png")))
                .thenThrow(new StorageException(StorageException.Code.ACCESS_DENIED));
        String id = id(); uploads.start(42L, request(id, 2)); queue.getFirst().run();
        var result = uploads.get(42L, id);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("권한");
        verify(storage, never()).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void differentActorsCannotQueueTheSameNormalizedProductEvenWithDifferentSelections() {
        var first = request(id(), 1);
        uploads.start(42L, first);
        var next = new ProductImageUploadRequest(id(), first.productCode(), first.brandCode(), " hiho861w2 ",
                List.of(new DownloadImageItemDto(null, id())));
        assertBusy(() -> uploads.start(43L, next));
        assertThat(queue).hasSize(1);
        verify(workspace, times(1)).prepareExport(any(), any());
    }

    @Test void unrelatedLegacyAndFlatPathsNeverParticipateInDuplicateDetection() {
        String id = id();
        uploads.start(42L, request(id, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, id).status()).isEqualTo("SUCCEEDED");
        verify(storage).head(StorageArea.PUBLIC, key(id, 1, ".jpg"));
        verify(storage).head(StorageArea.PUBLIC, key(id, 2, ".png"));
        verify(storage, never()).list(any(), any(), any(), anyInt());
        verify(storage, never()).head(StorageArea.PUBLIC, "products/HIHO861W2/01.jpg");
    }

    @Test void publicObjectsFromAnotherRequestCannotConfirmAnUncertainWriteEvenWhenBytesMatch() throws Exception {
        String id = id();
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(id, 1, ".jpg")), any(), any(), any()))
                .thenThrow(new StorageException(StorageException.Code.WRITE_UNCERTAIN));
        var matchingBytes = confirmed(id(), 1);
        when(storage.head(StorageArea.PUBLIC, key(id, 1, ".jpg"))).thenReturn(Optional.empty(), Optional.of(matchingBytes));
        uploads.start(42L, request(id, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, id).status()).isEqualTo("UNKNOWN");
        assertBusy(() -> uploads.start(43L, request(id(), 2)));
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void definitePreparationFailureReleasesTheProductForANewRequest() {
        when(workspace.prepareExport(any(), any())).thenReturn(() -> { throw new IllegalStateException("test failure"); });
        String first = id();
        uploads.start(42L, request(first, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, first).status()).isEqualTo("FAILED");
        when(workspace.prepareExport(any(), any())).thenReturn(() -> images(2));
        uploads.start(43L, request(id(), 2));
        assertThat(queue).hasSize(2);
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void partialSuccessAllowsNewRequestToSkipStoredFilesAndUploadRemainingFiles() {
        String first = id();
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(first, 2, ".png")), any(), any(), any()))
                .thenThrow(new StorageException(StorageException.Code.ACCESS_DENIED));
        uploads.start(42L, request(first, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, first).status()).isEqualTo("FAILED");
        when(storage.head(StorageArea.PUBLIC, key(first, 1, ".jpg"))).thenReturn(Optional.of(stored(key(first, 1, ".jpg"))));
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(first, 2, ".png")), any(), any(), any()))
                .thenReturn(stored(key(first, 2, ".png")));
        String next = id(); uploads.start(43L, request(next, 2)); queue.getLast().run();
        var result = uploads.get(43L, next);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::fileName).containsExactly("사이즈.png");
        assertThat(result.result().skippedFiles()).hasSize(1);
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), eq(key(first, 1, ".jpg")), any(), any(), any());
    }

    @Test void uncertainPrivateReservationNeverSendsImagesAndCanBeSafelyReleasedAfterConfirmation() {
        String first = id(), claimKey = "product-image-upload-reservations/HIHO861W2.json";
        var generation = new java.util.concurrent.atomic.AtomicReference<String>();
        when(storage.create(eq(StorageArea.PRIVATE), eq(claimKey), any(), any(), any()))
                .thenAnswer(call -> {
                    Map<String, String> metadata = call.getArgument(4);
                    generation.set(metadata.get("generation"));
                    throw new StorageException(StorageException.Code.WRITE_UNCERTAIN);
                });
        uploads.start(42L, request(first, 2)); queue.getFirst().run();
        assertThat(uploads.get(42L, first).status()).isEqualTo("UNKNOWN");
        assertBusy(() -> uploads.start(43L, request(id(), 2)));
        when(storage.head(StorageArea.PRIVATE, claimKey)).thenReturn(Optional.of(
                new ObjectMetadata(StorageArea.PRIVATE, claimKey, 1, "application/json", "other-generation-etag", null,
                        Map.of("request-id",first,"state","held","generation",id()))));
        assertThat(uploads.get(42L, first).status()).isEqualTo("UNKNOWN");
        verify(storage, never()).replace(eq(StorageArea.PRIVATE), any(), any(), any(), any(), any());
        when(storage.head(StorageArea.PRIVATE, claimKey)).thenReturn(Optional.of(
                new ObjectMetadata(StorageArea.PRIVATE, claimKey, 1, "application/json", "own-etag", null,
                        Map.of("request-id",first,"state","held","generation",generation.get()))));
        var result = uploads.get(42L, first);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("예약을 해제");
        verify(storage).replace(eq(StorageArea.PRIVATE), eq(claimKey), eq("own-etag"), any(), any(),
                argThat(metadata -> first.equals(metadata.get("request-id"))
                        && "released".equals(metadata.get("state"))
                        && generation.get().equals(metadata.get("generation"))));
        verify(storage, never()).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void privateReservationSurvivesMemoryExpiryAndBlocksARecreatedService() {
        String first = id(), claimKey = "product-image-upload-reservations/HIHO861W2.json";
        when(storage.head(StorageArea.PRIVATE, claimKey)).thenReturn(Optional.of(
                new ObjectMetadata(StorageArea.PRIVATE, claimKey, 1, "application/json", "held-etag", null,
                        Map.of("request-id",first,"state","held"))));
        var restarted = new ProductImageUploadService(workspace, storage, queue::add, 32 * 1024 * 1024L, clock);
        String next = id();
        restarted.start(43L, request(next, 2)); queue.getFirst().run();
        assertThat(restarted.get(43L, next).status()).isEqualTo("FAILED");
        assertThat(restarted.get(43L, next).error()).contains("예약이 사용 중");
        verify(storage, never()).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test void completedHistoryDoesNotBlockDeletedFilesAndSameRequestNeverReuploads() {
        var objects = statefulStorage();
        String first = id(); uploads.start(42L, request(first, 2)); queue.getLast().run();
        var result = uploads.get(42L, first);
        objects.keySet().removeIf(key -> key.startsWith("PUBLIC:")); // Simulated manual public-folder deletion.
        assertThat(uploads.start(42L, request(first, 2))).isEqualTo(result);
        assertThat(queue).hasSize(1);
        String next = id(); uploads.start(42L, request(next, 2)); queue.getLast().run();
        assertThat(uploads.get(42L, next).status()).isEqualTo("SUCCEEDED");
        assertThat(uploads.get(42L, next).result().files()).hasSize(2);
        verify(storage, times(4)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void allExistingFilesAreSkippedWithoutPublicWritesIncludingAfterRestart() {
        var objects = statefulStorage();
        for (var image : images(2)) {
            String key = key("", image.fileName());
            objects.put("PUBLIC:" + key, stored(key));
        }
        for (int i = 0; i < 2; i++) {
            if (i == 1) uploads = new ProductImageUploadService(workspace, storage, queue::add, 32 * 1024 * 1024L, clock);
            String next = id(); uploads.start(42L, request(next, 2)); queue.getLast().run();
            var result = uploads.get(42L, next);
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(result.result().files()).isEmpty();
            assertThat(result.result().skippedFiles()).hasSize(2);
        }
        verify(storage, never()).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void sameBasenameInDifferentCategoriesOrExtensionsIsNotDuplicate() {
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> List.of(
                new ExportImageDto("01.jpg", "image/jpeg", ".jpg", new byte[]{1}, ExportImageCategory.OFFICIAL),
                new ExportImageDto("01.jpg", "image/jpeg", ".jpg", new byte[]{2}, ExportImageCategory.PROCESSED),
                new ExportImageDto("01.png", "image/png", ".png", new byte[]{3}, ExportImageCategory.OFFICIAL)));
        String next = id(); uploads.start(42L, request(next, 3)); queue.getLast().run();
        assertThat(uploads.get(42L, next).status()).isEqualTo("SUCCEEDED");
        assertThat(uploads.get(42L, next).result().files()).extracting(ProductImageUploadJob.File::key).containsExactly(
                "products/HIHO861W2/official/01.jpg", "products/HIHO861W2/processed/01.jpg", "products/HIHO861W2/official/01.png");
    }

    @Test void objectCreatedAfterPreflightIsSkippedOnConditionalConflictWithoutOverwrite() {
        String next = id(), key = key(next, 1, ".jpg");
        when(storage.head(StorageArea.PUBLIC, key)).thenReturn(Optional.empty(), Optional.of(stored(key)));
        when(storage.create(eq(StorageArea.PUBLIC), eq(key), any(), any(), any()))
                .thenThrow(new StorageException(StorageException.Code.CONFLICT));
        uploads.start(42L, request(next, 2)); queue.getLast().run();
        var result = uploads.get(42L, next);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).hasSize(1);
        assertThat(result.result().skippedFiles()).extracting(ProductImageUploadJob.SkippedFile::key).containsExactly(key);
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), eq(key), any(), any(), any());
        verify(storage, never()).replace(eq(StorageArea.PUBLIC), any(), any(), any(), any(), any());
    }

    @Test void conditionalConflictWithoutConfirmableObjectRemainsUnknownAndDoesNotExpire() {
        String next = id();
        when(storage.create(eq(StorageArea.PUBLIC), any(), any(), any(), any()))
                .thenThrow(new StorageException(StorageException.Code.CONFLICT));
        uploads.start(42L, request(next, 2)); queue.getLast().run();
        assertThat(uploads.get(42L, next).status()).isEqualTo("UNKNOWN");
        clock.advance(Duration.ofHours(5)); uploads.cleanup();
        assertThat(uploads.start(42L, request(next, 2)).status()).isEqualTo("UNKNOWN");
        assertBusy(() -> uploads.start(43L, request(id(), 2)));
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void uncertainFinalWriteCountsSkippedFilesWhenDeterminingCompletion() throws Exception {
        String next = id();
        when(storage.head(StorageArea.PUBLIC, key(next, 1, ".jpg"))).thenReturn(Optional.of(stored(key(next, 1, ".jpg"))));
        when(storage.head(StorageArea.PUBLIC, key(next, 2, ".png"))).thenReturn(Optional.empty(), Optional.of(confirmed(next, 2)));
        when(storage.create(eq(StorageArea.PUBLIC), eq(key(next, 2, ".png")), any(), any(), any()))
                .thenThrow(new StorageException(StorageException.Code.WRITE_UNCERTAIN));
        uploads.start(42L, request(next, 2)); queue.getLast().run();
        var result = uploads.get(42L, next);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).hasSize(1);
        assertThat(result.result().skippedFiles()).hasSize(1);
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void releaseFailureAfterConfirmedUploadsIsRecoverableWithoutRepeatingPublicWrites() {
        statefulStorage();
        var failRelease = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            if (failRelease.get()) throw new StorageException(StorageException.Code.WRITE_UNCERTAIN);
            return stored("released");
        }).when(storage).replace(eq(StorageArea.PRIVATE), any(), any(), any(), any(), any());
        String next = id(); uploads.start(42L, request(next, 2)); queue.getLast().run();
        var pending = uploads.get(42L, next);
        assertThat(pending.status()).isEqualTo("UNKNOWN");
        assertThat(pending.result().files()).hasSize(2);
        assertThat(pending.error()).contains("예약 해제");
        assertBusy(() -> uploads.start(43L, request(id(), 2)));
        failRelease.set(false);
        assertThat(uploads.get(42L, next).status()).isEqualTo("SUCCEEDED");
        verify(storage, times(2)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void releaseFailureAfterPartialFailureRestoresFailureAndConfirmedFiles() {
        statefulStorage();
        var failRelease = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            if (failRelease.get()) throw new StorageException(StorageException.Code.UNAVAILABLE);
            return stored("released");
        }).when(storage).replace(eq(StorageArea.PRIVATE), any(), any(), any(), any(), any());
        doThrow(new StorageException(StorageException.Code.ACCESS_DENIED))
                .when(storage).create(eq(StorageArea.PUBLIC), eq(key("", 2, ".png")), any(), any(), any());
        String next = id(); uploads.start(42L, request(next, 2)); queue.getLast().run();
        assertThat(uploads.get(42L, next).status()).isEqualTo("UNKNOWN");
        failRelease.set(false);
        var result = uploads.get(42L, next);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.result().files()).hasSize(1);
        assertThat(result.error()).contains("권한");
        verify(storage, times(2)).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
    }

    @Test void missingSizeOnlyCanBeAddedDespiteCompletedHistoryAndExistingOtherImages() {
        var objects = statefulStorage();
        String first = id(); uploads.start(42L, request(first, 2)); queue.getLast().run();
        objects.remove("PUBLIC:" + key(first, "사이즈.png"));
        when(workspace.prepareExport(anyLong(), any())).thenReturn(() -> List.of(images(2).getLast()));
        String next = id();
        var selection = new ProductImageUploadRequest(next, "HIHO6F861W2", "HAZZYS", "HIHO861W2",
                List.of(new DownloadImageItemDto(null, id())));
        uploads.start(42L, selection); queue.getLast().run();
        var result = uploads.get(42L, next);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).extracting(ProductImageUploadJob.File::fileName).containsExactly("사이즈.png");
        assertThat(result.result().skippedFiles()).isEmpty();
        verify(storage, times(1)).create(eq(StorageArea.PUBLIC), eq(key(first, "01.jpg")), any(), any(), any());
        verify(storage, times(2)).create(eq(StorageArea.PUBLIC), eq(key(first, "사이즈.png")), any(), any(), any());
    }

    @Test void lostReleaseResponseAfterCommitRestoresAllSkippedSuccessWithoutPublicWrites() {
        var objects = statefulStorage();
        for (var image : images(2)) {
            String key = key("", image.fileName()); objects.put("PUBLIC:" + key, stored(key));
        }
        doAnswer(call -> {
            String key = call.getArgument(1); byte[] content = call.getArgument(3);
            objects.put("PRIVATE:" + key, new ObjectMetadata(StorageArea.PRIVATE, key, content.length,
                    call.getArgument(4), id(), clock.instant(), call.getArgument(5)));
            throw new StorageException(StorageException.Code.WRITE_UNCERTAIN);
        }).when(storage).replace(eq(StorageArea.PRIVATE), any(), any(), any(), any(), any());
        String next = id(); uploads.start(42L, request(next, 2)); queue.getLast().run();
        var result = uploads.get(42L, next);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result().files()).isEmpty();
        assertThat(result.result().skippedFiles()).hasSize(2);
        verify(storage, never()).create(eq(StorageArea.PUBLIC), any(), any(), any(), any());
        verify(storage, times(1)).replace(eq(StorageArea.PRIVATE), any(), any(), any(), any(), any());
    }

    private Map<String, ObjectMetadata> statefulStorage() {
        Map<String, ObjectMetadata> objects = new HashMap<>();
        when(storage.head(any(), anyString())).thenAnswer(call -> Optional.ofNullable(objects.get(call.getArgument(0) + ":" + call.getArgument(1))));
        when(storage.create(any(), anyString(), any(), anyString(), anyMap())).thenAnswer(call -> {
            StorageArea area = call.getArgument(0); String key = call.getArgument(1); byte[] content = call.getArgument(2);
            if (objects.containsKey(area + ":" + key)) throw new StorageException(StorageException.Code.CONFLICT);
            ObjectMetadata object = new ObjectMetadata(area, key, content.length, call.getArgument(3), id(), clock.instant(), call.getArgument(4));
            objects.put(area + ":" + key, object); return object;
        });
        when(storage.replace(any(), anyString(), anyString(), any(), anyString(), anyMap())).thenAnswer(call -> {
            StorageArea area = call.getArgument(0); String key = call.getArgument(1); byte[] content = call.getArgument(3);
            ObjectMetadata current = objects.get(area + ":" + key);
            if (current == null || !current.eTag().equals(call.getArgument(2))) throw new StorageException(StorageException.Code.CONFLICT);
            ObjectMetadata object = new ObjectMetadata(area, key, content.length, call.getArgument(4), id(), clock.instant(), call.getArgument(5));
            objects.put(area + ":" + key, object); return object;
        });
        return objects;
    }

    private ProductImageUploadRequest destination(ProductImageUploadRequest source, String code) {
        return new ProductImageUploadRequest(source.requestId(), source.productCode(), source.brandCode(), code, source.images());
    }
    private static ObjectMetadata stored(String key) {
        return new ObjectMetadata(StorageArea.PUBLIC, key, 3, "image/jpeg", "etag", Instant.EPOCH, Map.of());
    }
    private static void assertBusy(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProductImageUploadFailure.class,
                e -> assertThat(e.kind()).isEqualTo(ProductImageUploadFailure.Kind.BUSY));
    }

    private static ObjectMetadata confirmed(String id, int sequence) throws Exception {
        ExportImageDto image = images(sequence).getLast();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.bytes()));
        return new ObjectMetadata(StorageArea.PUBLIC, key(id, sequence, image.fileExtension()), image.byteSize(), image.contentType(), "etag", Instant.now(), Map.of("sha256",sha,"request-id",id));
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
