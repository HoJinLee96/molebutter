package cc.ataglace.molebutter.media.internal;

import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public final class ServerImageInspection implements ImageInspection {
    private final R2Images r2;
    private final ImageAssets assets;
    private final BusinessAccess access;
    private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public ServerImageInspection(R2Images r2, ImageAssets assets, BusinessAccess access) {
        this(r2, assets, access, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    ServerImageInspection(R2Images r2, ImageAssets assets, BusinessAccess access, HttpClient http) {
        this.r2 = r2;
        this.assets = assets;
        this.access = access;
        this.http = http;
    }
    private record File(String mime, int width, int height, long bytes, String sha256, boolean existingOnly) {
    }
    public List<Check> inspect(Long actor, List<Input> images) {
        access.productActor(actor, true);
        if (images == null || images.size() > 500)
            throw new InputValidationFailure("검사할 이미지는 500개 이하로 선택해 주세요.");
        var cache = new HashMap<String, File>();
        var publicFailures = new HashMap<String, String>();
        var published = new HashSet<String>();
        var failures = new HashMap<String, String>();
        var checks = new ArrayList<Check>();
        for (var image : images) {
            String key = image.assetId() != null ? "asset:" + image.assetId() : "url:" + image.url(),
                    failureKey = key + "|" + image.existing();
            File file = null;
            try {
                if (failures.containsKey(failureKey))
                    throw new InputValidationFailure(failures.get(failureKey));
                file = cache.get(key);
                if (file == null) {
                    file = read(actor, image);
                    cache.put(key, file);
                }
                if (file.existingOnly() && !image.existing())
                    throw new InputValidationFailure("새 이미지에는 R2 원본 또는 앱 편집본을 선택해 주세요.");
                if (image.assetId() != null) {
                    if (publicFailures.containsKey(image.assetId()))
                        throw new InputValidationFailure(publicFailures.get(image.assetId()));
                    if (published.add(image.assetId()))
                        try {
                            if (!Objects.equals(file.sha256(),
                                    remote(assets.submissionUrl(actor, image.assetId()), false).sha256()))
                                throw new InputValidationFailure("공개 이미지가 보관한 편집본과 다릅니다. 공개 이미지 경로를 확인해 주세요.");
                        } catch (RuntimeException failure) {
                            publicFailures.put(image.assetId(), failure.getMessage());
                            throw failure;
                        }
                }
                var errors = ImageRules.errors(image.role(), file.mime(), file.width(), file.height(), file.bytes());
                var warnings = ImageRules.warnings(image.role(), file.width(), file.height());
                checks.add(check(image, errors.isEmpty() ? "PASS" : "FAIL", file, errors, warnings));
            } catch (RuntimeException failure) {
                String message = failure instanceof InputValidationFailure
                        ? failure.getMessage()
                        : "이미지 실제 파일을 읽지 못했습니다. 다시 조회해 주세요.";
                if (file == null)
                    failures.put(failureKey, message);
                checks.add(check(image, "UNVERIFIED", file, List.of(message), List.of()));
            }
        }
        return List.copyOf(checks);
    }
    private File read(Long actor, Input image) {
        if (image.assetId() != null) {
            var c = assets.readEdited(actor, image.assetId());
            var a = LocalImageAssets.inspect(c.bytes());
            return new File(a.mimeType(), a.width(), a.height(), c.bytes().length, hash(c.bytes()), false);
        }
        try {
            var a = r2.resolve(actor, image.url());
            return new File(a.mimeType(), a.width(), a.height(), a.bytes(), null, false);
        } catch (InputValidationFailure invalid) {
            if (!image.existing() || !cdn(image.url()))
                throw invalid;
            return remote(image.url(), true);
        }
    }
    private File remote(String value, boolean cdn) {
        byte[] bytes = remoteBytes(value, cdn);
        var a = LocalImageAssets.inspect(bytes);
        return new File(a.mimeType(), a.width(), a.height(), bytes.length, hash(bytes), cdn);
    }
    private byte[] remoteBytes(String value, boolean cdn) {
        try {
            var uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443)
                throw new IllegalArgumentException();
            if (cdn && !cdn(value))
                throw new IllegalArgumentException();
            for (var address : InetAddress.getAllByName(uri.getHost()))
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress())
                    throw new IllegalArgumentException();
            var response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                            .header("User-Agent", "molebutter-image-check").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200)
                    throw new IllegalArgumentException();
                byte[] bytes = stream.readNBytes(LocalImageAssets.MAX_BYTES + 1);
                if (bytes.length > LocalImageAssets.MAX_BYTES)
                    throw new IllegalArgumentException();
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InputValidationFailure("이미지 조회가 중단되었습니다.");
        } catch (Exception e) {
            throw new InputValidationFailure("외부에서 이미지 주소를 읽지 못했습니다. 공개 HTTPS 주소와 접근 설정을 확인해 주세요.");
        }
    }
    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static boolean cdn(String value) {
        try {
            String host = URI.create(value).getHost();
            return host != null && (host.endsWith(".coupangcdn.com"));
        } catch (RuntimeException e) {
            return false;
        }
    }
    private static Check check(Input i, String status, File f, List<String> reasons, List<String> warnings) {
        return new Check(i.role(), i.optionId(), i.imageId(), i.url(), status, f == null ? null : f.width(),
                f == null ? null : f.height(), f == null ? null : f.bytes(), reasons, warnings);
    }
}
