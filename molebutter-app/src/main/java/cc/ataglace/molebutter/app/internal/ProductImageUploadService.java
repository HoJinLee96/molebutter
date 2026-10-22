package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.storage.api.*;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** App composition: the imaging module prepares owned bytes, storage creates public R2 objects. */
@Service
public class ProductImageUploadService {
    static final Duration RETENTION = Duration.ofHours(4);
    static final int MAX_JOBS = 32;
    static final int MAX_RUNNING = 2;
    static final long MAX_CONTENT_BYTES = 64L * 1024 * 1024;
    private final Map<String, Entry> jobs = new LinkedHashMap<>();
    private final Set<String> checking = new HashSet<>();
    private final ProductImageWorkspace workspace;
    private final ObjectStorage storage;
    private final ProductImageUploadReservations reservations;
    private final Executor executor;
    private final long maxObjectBytes;
    private final Clock clock;

    @Autowired
    public ProductImageUploadService(ProductImageWorkspace workspace, ObjectStorage storage,
            @Qualifier("productImageUploadExecutor") Executor executor,
            @Value("${cloudflare.r2.max-object-bytes:33554432}") long maxObjectBytes) {
        this(workspace, storage, executor, maxObjectBytes, Clock.systemUTC());
    }
    ProductImageUploadService(ProductImageWorkspace workspace, ObjectStorage storage,
            Executor executor, long maxObjectBytes, Clock clock) {
        if (maxObjectBytes <= 0 || maxObjectBytes > MAX_CONTENT_BYTES) throw new IllegalArgumentException("Invalid image upload size limit");
        this.workspace = workspace; this.storage = storage; this.executor = executor;
        this.reservations = new ProductImageUploadReservations(storage);
        this.maxObjectBytes = maxObjectBytes; this.clock = clock;
    }

    public synchronized ProductImageUploadJob start(Long actor, ProductImageUploadRequest input) {
        actor(actor); ProductImageUploadRequest request = normalized(input); cleanup();
        Entry existing = jobs.get(request.requestId());
        if (existing != null) {
            if (!existing.actor().equals(actor)) throw notFound();
            if (!existing.request().equals(request)) throw new ProductImageUploadFailure(ProductImageUploadFailure.Kind.CONFLICT,
                    "같은 업로드 요청 ID의 내용이 변경되었습니다. 기존 업로드 결과를 확인해주세요.");
            return existing.job();
        }
        if (jobs.values().stream().anyMatch(entry -> entry.request().uploadProductCode().equals(request.uploadProductCode())
                && ("RUNNING".equals(entry.job().status()) || "UNKNOWN".equals(entry.job().status())))) {
            throw new ProductImageUploadFailure(ProductImageUploadFailure.Kind.BUSY,
                    "이 상품의 업로드가 진행 중이거나 결과 확인이 필요합니다. 기존 작업의 결과를 확인해주세요.");
        }
        long running = jobs.values().stream().filter(e -> !e.job().finished()).count();
        if (running >= MAX_RUNNING || jobs.values().stream().anyMatch(e -> e.actor().equals(actor) && !e.job().finished())) {
            throw new ProductImageUploadFailure(ProductImageUploadFailure.Kind.BUSY, "이미 진행 중인 이미지 업로드가 있습니다. 완료 후 다시 시도해주세요.");
        }
        // Unexpired IDs are never evicted to make room: they prevent duplicate writes after a lost response.
        if (jobs.size() >= MAX_JOBS) throw new ProductImageUploadFailure(ProductImageUploadFailure.Kind.BUSY,
                "업로드 결과 보관 공간이 가득 찼습니다. 잠시 후 다시 시도해주세요.");
        publicUrl(key(request, ExportImageCategory.OFFICIAL, "01.png")); // Pure configuration check: no S3 request or source-image download.
        DownloadRequestDto selection = new DownloadRequestDto(request.productCode(), request.brandCode(), null, false, false, null, request.images());
        ImageExportPlan plan = workspace.prepareExport(actor, selection);
        ProductImageUploadJob job = new ProductImageUploadJob(request.requestId(), "RUNNING", "이미지 업로드를 준비하고 있습니다.",
                clock.instant(), null, new ProductImageUploadJob.Result(request.uploadProductCode(), List.of(), request.images().size()), null);
        jobs.put(request.requestId(), new Entry(actor, request, job, List.of(), null, null, null));
        try { executor.execute(() -> run(request, plan)); }
        catch (RejectedExecutionException failure) {
            jobs.remove(request.requestId());
            throw new ProductImageUploadFailure(ProductImageUploadFailure.Kind.BUSY, "업로드 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요.");
        }
        return jobs.get(request.requestId()).job();
    }

    public ProductImageUploadJob get(Long actor, String id) {
        actor(actor); String canonical = requestId(id);
        Entry entry;
        synchronized (this) {
            cleanup(); entry = jobs.get(canonical);
            if (entry == null || !entry.actor().equals(actor)) throw notFound();
            if (!"UNKNOWN".equals(entry.job().status()) || entry.uncertainIndex() == null
                    || checking.size() >= MAX_RUNNING || !checking.add(canonical)) return entry.job();
        }
        // Do not hold the registry lock across network checks; image writes are never repeated.
        try { reconcile(entry); }
        finally { synchronized (this) { checking.remove(canonical); } }
        synchronized (this) {
            Entry current = jobs.get(canonical);
            return current == null ? entry.job() : current.job();
        }
    }

    /** Read-only completed ownership linkage. Never reconciles or writes a reservation. */
    synchronized Optional<Map<DownloadImageItemDto,ProductImageUploadJob.File>> recent(Long actor,String code,String brand,String storageCode) {
        cleanup();
        var entry=jobs.values().stream().filter(e->e.actor().equals(actor)&&"SUCCEEDED".equals(e.job().status())
            &&e.request().productCode().equals(code)&&e.request().brandCode().equals(brand)&&e.request().uploadProductCode().equals(storageCode))
            .max(Comparator.comparing(e->e.job().finishedAt())).orElse(null);
        if(entry==null||entry.expected().size()!=entry.request().images().size())return Optional.empty();
        var result=new LinkedHashMap<DownloadImageItemDto,ProductImageUploadJob.File>();
        for(int i=0;i<entry.request().images().size();i++)result.put(entry.request().images().get(i),entry.expected().get(i).file());
        return Optional.of(Map.copyOf(result));
    }
    synchronized boolean matchesRecent(Long actor,String code,String brand,String storageCode,String key,byte[] bytes) {
        cleanup();
        var entry=jobs.values().stream().filter(e->e.actor().equals(actor)&&"SUCCEEDED".equals(e.job().status())&&e.request().productCode().equals(code)&&e.request().brandCode().equals(brand)&&e.request().uploadProductCode().equals(storageCode))
            .max(Comparator.comparing(e->e.job().finishedAt())).orElse(null);
        return entry!=null&&entry.expected().stream().anyMatch(e->e.file().key().equals(key)&&e.sha256().equals(digest(bytes)));
    }
    private void run(ProductImageUploadRequest request, ImageExportPlan plan) {
        List<ProductImageUploadJob.File> completed = new ArrayList<>();
        List<ProductImageUploadJob.SkippedFile> skipped = new ArrayList<>();
        boolean writing = false;
        boolean acquired = false;
        Integer uncertainIndex = null;
        String generation = UUID.randomUUID().toString();
        Resolution resolution = new Resolution("SUCCEEDED", "이미지 업로드가 완료되었습니다.", null);
        try {
            List<ExportImageDto> images = plan.prepare();
            if (images == null || images.size() != request.images().size()) {
                throw new ImagingFailure(ImagingFailure.Kind.INTERNAL, "업로드 이미지 목록을 준비하지 못했습니다.");
            }
            List<ExpectedFile> destinations = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            long bytes = 0;
            for (ExportImageDto image : images) {
                if (image == null || image.category() == null || image.byteSize() == 0 || image.byteSize() > maxObjectBytes
                        || !validFormat(image.contentType(), image.fileExtension())
                        || !validFileName(image.fileName(), image.fileExtension())) {
                    throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "업로드 이미지 파일명, 형식 또는 파일 크기 제한을 확인해주세요.");
                }
                String key = key(request, image.category(), image.fileName());
                if (!keys.add(key)) throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "업로드 이미지 저장 경로가 중복됩니다.");
                bytes += image.byteSize();
                if (bytes > MAX_CONTENT_BYTES) throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "전체 업로드 이미지 크기는 64MB 이하여야 합니다.");
                destinations.add(new ExpectedFile(new ProductImageUploadJob.File(image.fileName(), key, publicUrl(key).toString()),
                        image.contentType(), image.byteSize(), digest(image.bytes())));
            }
            destinations(request.requestId(), destinations, generation);
            // The private claim protects active/uncertain work, not previously completed uploads.
            uncertainIndex = -1;
            writing = true;
            try { reservations.acquire(request.uploadProductCode(), request.requestId(), generation); }
            catch (ProductImageUploadFailure busy) { writing = false; uncertainIndex = null; throw busy; }
            acquired = true;
            writing = false;
            uncertainIndex = null;
            // Check every exact key before the first public write. Read failures never mean absence.
            Set<String> existingKeys = new HashSet<>();
            for (ExpectedFile destination : destinations) {
                if (storage.head(StorageArea.PUBLIC, destination.file().key()).isPresent()) {
                    existingKeys.add(destination.file().key());
                    skipped.add(skipped(destination.file()));
                }
            }
            skipped(request.requestId(), skipped);
            for (int i = 0; i < images.size(); i++) {
                ExpectedFile destination = destinations.get(i);
                if (existingKeys.contains(destination.file().key())) continue;
                ExportImageDto image = images.get(i);
                uncertainIndex = i;
                writing = true;
                try {
                    storage.create(StorageArea.PUBLIC, destination.file().key(), image.bytes(), image.contentType(),
                            Map.of("sha256", destination.sha256(), "request-id", request.requestId()));
                } catch (StorageException failure) {
                    if (failure.code() != StorageException.Code.CONFLICT) throw failure;
                    // A definite conditional rejection with an existing object is a duplicate, not our success.
                    boolean exists;
                    try { exists = storage.head(StorageArea.PUBLIC, destination.file().key()).isPresent(); }
                    catch (Exception unavailable) { throw failure; }
                    if (!exists) throw failure;
                    writing = false;
                    uncertainIndex = null;
                    skipped.add(skipped(destination.file()));
                    skipped(request.requestId(), skipped);
                    continue;
                }
                writing = false;
                uncertainIndex = null;
                completed.add(destination.file());
                update(request.requestId(), "RUNNING", "이미지를 업로드하고 있습니다.", completed, null);
            }
        } catch (StorageException failure) {
            boolean uncertain = writing && (failure.code() == StorageException.Code.WRITE_UNCERTAIN
                    || failure.code() == StorageException.Code.CONFLICT);
            if (uncertain) uncertain(request.requestId(), uncertainIndex);
            resolution = new Resolution(uncertain ? "UNKNOWN" : "FAILED",
                    uncertain ? "업로드 결과 확인이 필요합니다." : "이미지 업로드가 중단되었습니다.",
                    uncertain ? "일부 파일의 저장 결과를 확정할 수 없습니다. 재업로드하지 말고 저장 결과를 확인해주세요."
                            : storageMessage(failure.code()));
        } catch (Exception failure) {
            String message = failure instanceof ImagingFailure imaging ? imaging.getMessage()
                    : failure instanceof ProductImageUploadFailure safe ? safe.getMessage() : "이미지 업로드를 완료하지 못했습니다.";
            if (writing) message = "일부 파일의 저장 결과를 확정할 수 없습니다. 재업로드하지 말고 저장 결과를 확인해주세요.";
            if (writing) uncertain(request.requestId(), uncertainIndex);
            resolution = new Resolution(writing ? "UNKNOWN" : "FAILED",
                    writing ? "업로드 결과 확인이 필요합니다." : "이미지 업로드가 중단되었습니다.", message);
        }
        if (acquired && !"UNKNOWN".equals(resolution.status())) finish(request, generation, completed, resolution);
        else update(request.requestId(), resolution.status(), resolution.message(), completed, resolution.error());
    }

    private static ProductImageUploadJob.SkippedFile skipped(ProductImageUploadJob.File file) {
        return new ProductImageUploadJob.SkippedFile(file.fileName(), file.key(), file.url(), "EXISTS");
    }

    private void finish(ProductImageUploadRequest request, String generation, List<ProductImageUploadJob.File> files, Resolution resolution) {
        try { reservations.release(request.uploadProductCode(), request.requestId(), generation); }
        catch (Exception unavailable) {
            synchronized (this) {
                Entry entry = jobs.get(request.requestId());
                jobs.put(request.requestId(), new Entry(entry.actor(), entry.request(), entry.job(), entry.expected(), -2, generation, resolution));
                update(request.requestId(), "UNKNOWN", "업로드 예약 해제 확인이 필요합니다.", files,
                        "파일 처리 결과는 확인했지만 업로드 예약 해제를 확인하지 못했습니다. 결과 확인을 눌러주세요.");
            }
            return;
        }
        update(request.requestId(), resolution.status(), resolution.message(), files, resolution.error());
    }

    private synchronized void skipped(String id, List<ProductImageUploadJob.SkippedFile> files) {
        Entry entry = jobs.get(id);
        ProductImageUploadJob before = entry.job();
        ProductImageUploadJob job = new ProductImageUploadJob(id, before.status(), before.message(), before.startedAt(), before.finishedAt(),
                new ProductImageUploadJob.Result(entry.request().uploadProductCode(), before.result().files(), before.result().totalCount(), files), before.error());
        jobs.put(id, new Entry(entry.actor(), entry.request(), job, entry.expected(), entry.uncertainIndex(), entry.generation(), entry.resolution()));
    }

    private synchronized void update(String id, String status, String message, List<ProductImageUploadJob.File> files, String error) {
        Entry entry = jobs.get(id); if (entry == null) return;
        ProductImageUploadJob before = entry.job();
        ProductImageUploadJob job = new ProductImageUploadJob(id, status, message, before.startedAt(), "RUNNING".equals(status) ? null : clock.instant(),
                new ProductImageUploadJob.Result(entry.request().uploadProductCode(), files, entry.request().images().size(), before.result().skippedFiles()), error);
        jobs.put(id, new Entry(entry.actor(), entry.request(), job, entry.expected(), entry.uncertainIndex(), entry.generation(), entry.resolution()));
    }

    private synchronized void destinations(String id, List<ExpectedFile> expected, String generation) {
        Entry entry = jobs.get(id);
        if (entry != null) jobs.put(id, new Entry(entry.actor(), entry.request(), entry.job(), List.copyOf(expected), null, generation, null));
    }
    private synchronized void uncertain(String id, Integer index) {
        Entry entry = jobs.get(id);
        if (entry != null) jobs.put(id, new Entry(entry.actor(), entry.request(), entry.job(), entry.expected(), index, entry.generation(), entry.resolution()));
    }
    private void reconcile(Entry entry) {
        if (entry.uncertainIndex() < 0) {
            try {
                String product = entry.request().uploadProductCode(), id = entry.request().requestId();
                if (reservations.heldBy(product, id, entry.generation())) reservations.release(product, id, entry.generation());
                else if (!reservations.releasedBy(product, id, entry.generation())) return;
            } catch (Exception unavailable) { return; }
            synchronized (this) {
                if (jobs.get(entry.job().id()) != entry) return;
                Resolution resolution = entry.resolution() != null ? entry.resolution() : new Resolution("FAILED",
                        "이미지 저장 전에 업로드가 중단되었습니다.", "파일을 저장하지 않았으며 업로드 예약을 해제했습니다. 다시 업로드할 수 있습니다.");
                update(entry.job().id(), resolution.status(), resolution.message(), entry.job().result().files(), resolution.error());
            }
            return;
        }
        ExpectedFile expected = entry.expected().get(entry.uncertainIndex());
        Optional<ObjectMetadata> stored;
        try { stored = storage.head(StorageArea.PUBLIC, expected.file().key()); }
        catch (Exception unavailable) { return; }
        boolean matches = stored.filter(metadata -> metadata.size() == expected.size()
                && expected.contentType().equals(metadata.contentType())
                && expected.sha256().equals(metadata.metadata().get("sha256"))
                && entry.request().requestId().equals(metadata.metadata().get("request-id"))).isPresent();
        // A timed-out write may complete after an empty HEAD. Never repeat it or mistake another owner's file for ours.
        if (!matches) return;
        List<ProductImageUploadJob.File> files = new ArrayList<>(entry.job().result().files());
        files.add(expected.file());
        boolean complete = files.size() + entry.job().result().skippedFiles().size() == entry.request().images().size();
        Resolution resolution = new Resolution(complete ? "SUCCEEDED" : "FAILED",
                complete ? "이미지 업로드가 확인되었습니다." : "업로드 결과를 확인했습니다.",
                complete ? null : "일부 파일의 저장이 확인되었습니다. 나머지 파일은 전송하지 않았습니다. 새 요청에서 남은 파일을 업로드할 수 있습니다.");
        finish(entry.request(), entry.generation(), files, resolution);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private URI publicUrl(String key) {
        try {
            return storage.publicUrl(StorageArea.PUBLIC, key).orElseThrow(() -> new StorageException(StorageException.Code.NOT_CONFIGURED));
        } catch (StorageException failure) {
            if (failure.code() == StorageException.Code.NOT_CONFIGURED) throw new ProductImageUploadFailure(ProductImageUploadFailure.Kind.NOT_CONFIGURED,
                    "R2 업로드가 설정되지 않았습니다. 공개 버킷 molebutter의 R2 설정을 활성화한 후 다시 시도해주세요.");
            throw failure;
        }
    }

    private static ProductImageUploadRequest normalized(ProductImageUploadRequest request) {
        if (request == null) invalid("업로드 요청이 필요합니다.");
        String source = code(request.productCode(), "조회 상품코드");
        String target = code(request.uploadProductCode(), "업로드 상품코드");
        String brand = request.brandCode() == null || request.brandCode().isBlank() ? "DAKS" : request.brandCode().trim().toUpperCase(Locale.ROOT);
        if (request.images() == null || request.images().isEmpty() || request.images().size() > 64) invalid("업로드할 이미지를 1개 이상, 64개 이하로 선택해주세요.");
        for (DownloadImageItemDto image : request.images()) {
            if (image == null || (image.imageIndex() == null) == (image.generatedImageId() == null)) invalid("업로드 이미지 항목을 확인해주세요.");
            if (image.imageIndex() != null && (image.imageIndex() < 0 || image.sourceImageUrl() == null || image.sourceImageUrl().isBlank()
                    || image.sourceImageUrl().length() > 2048)) invalid("조회한 원본 이미지 주소를 확인해주세요.");
            if (image.generatedImageId() != null) {
                requestId(image.generatedImageId());
                if (image.sourceImageUrl() != null) invalid("생성 이미지 항목을 확인해주세요.");
            }
        }
        return new ProductImageUploadRequest(requestId(request.requestId()), source, brand, target, request.images());
    }
    private static String code(String value, String label) {
        if (value == null || !value.trim().matches("[A-Za-z0-9_-]{4,40}")) invalid(label + "는 영문, 숫자, 밑줄, 하이픈 4~40자로 입력해주세요.");
        return value.trim().toUpperCase(Locale.ROOT);
    }
    private static String requestId(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) invalid("업로드 요청 ID를 확인해주세요.");
        return UUID.fromString(value).toString();
    }
    private static void actor(Long actor) { if (actor == null || actor <= 0) invalid("로그인이 필요합니다."); }
    private static void invalid(String message) { throw new ProductImageUploadFailure(ProductImageUploadFailure.Kind.INVALID_INPUT, message); }
    private static ProductImageUploadFailure notFound() { return new ProductImageUploadFailure(ProductImageUploadFailure.Kind.NOT_FOUND, "업로드 작업을 찾을 수 없습니다."); }
    private static String key(ProductImageUploadRequest request, ExportImageCategory category, String fileName) {
        return prefix(request) + category.directory() + "/" + fileName;
    }
    private static String prefix(ProductImageUploadRequest request) { return "products/" + request.uploadProductCode() + "/"; }

    private static boolean validFileName(String name, String extension) {
        return name != null && name.matches("(?:[0-9]{2}|사이즈(?:_[0-9]+)?|상품정보(?:_[0-9]+)?)\\.(?:jpg|png|gif|webp|avif)")
                && name.endsWith(extension);
    }
    private static boolean validFormat(String type, String extension) {
        return switch (type == null ? "" : type) {
            case "image/jpeg" -> ".jpg".equals(extension); case "image/png" -> ".png".equals(extension);
            case "image/gif" -> ".gif".equals(extension); case "image/webp" -> ".webp".equals(extension);
            case "image/avif" -> ".avif".equals(extension); default -> false;
        };
    }
    private static String storageMessage(StorageException.Code code) {
        return switch (code) {
            case ACCESS_DENIED -> "R2 공개 이미지 및 비공개 업로드 예약의 저장 권한을 확인해주세요.";
            case NOT_CONFIGURED -> "R2 공개·비공개 버킷 업로드 설정을 확인해주세요.";
            case TOO_LARGE -> "R2 파일 크기 제한을 초과했습니다. 파일 크기를 줄여주세요.";
            default -> "R2 업로드 요청이 실패했습니다. 저장된 파일 목록을 확인해주세요.";
        };
    }
    @Scheduled(fixedDelayString = "${imaging.cleanup-delay-ms:60000}")
    public synchronized void cleanup() {
        Instant cutoff = clock.instant().minus(RETENTION);
        jobs.values().removeIf(e -> e.job().finished() && !"UNKNOWN".equals(e.job().status()) && !checking.contains(e.job().id())
                && !e.job().finishedAt().isAfter(cutoff));
    }
    private record ExpectedFile(ProductImageUploadJob.File file, String contentType, long size, String sha256) { }
    private record Entry(Long actor, ProductImageUploadRequest request, ProductImageUploadJob job,
                         List<ExpectedFile> expected, Integer uncertainIndex, String generation, Resolution resolution) { }
    private record Resolution(String status, String message, String error) { }
}
