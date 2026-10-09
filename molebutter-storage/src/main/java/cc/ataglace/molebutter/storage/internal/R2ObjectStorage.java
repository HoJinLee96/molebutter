package cc.ataglace.molebutter.storage.internal;

import cc.ataglace.molebutter.storage.api.ObjectMetadata;
import cc.ataglace.molebutter.storage.api.ObjectPage;
import cc.ataglace.molebutter.storage.api.ObjectStorage;
import cc.ataglace.molebutter.storage.api.StorageArea;
import cc.ataglace.molebutter.storage.api.StorageException;
import cc.ataglace.molebutter.storage.api.StoredObject;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class R2ObjectStorage implements ObjectStorage {
    private final S3Client publicClient;
    private final S3Client privateClient;
    private final String publicBucket;
    private final String privateBucket;
    private final URI publicBaseUrl;
    private final int maxObjectBytes;

    R2ObjectStorage(S3Client client, R2ConfigurationProperties properties) {
        this(client, client, properties);
    }

    R2ObjectStorage(S3Client publicClient, S3Client privateClient, R2ConfigurationProperties properties) {
        properties.validatedEndpoint();
        this.publicClient = publicClient;
        this.privateClient = privateClient;
        this.publicBucket = properties.getPublicBucket();
        this.privateBucket = properties.getPrivateBucket();
        this.publicBaseUrl = properties.validatedPublicBaseUrl();
        this.maxObjectBytes = (int) properties.getMaxObjectBytes();
    }

    @Override
    public ObjectMetadata create(StorageArea area, String key, byte[] content, String contentType,
                                 Map<String, String> metadata) {
        return write(area, key, null, content, contentType, metadata);
    }

    @Override
    public ObjectMetadata replace(StorageArea area, String key, String expectedETag, byte[] content,
                                  String contentType, Map<String, String> metadata) {
        StorageValidation.eTag(expectedETag);
        return write(area, key, expectedETag, content, contentType, metadata);
    }

    private ObjectMetadata write(StorageArea area, String key, String expectedETag, byte[] content,
                                 String contentType, Map<String, String> metadata) {
        String bucket = bucket(area);
        StorageValidation.key(key);
        StorageValidation.require(content != null);
        if (content.length > maxObjectBytes) throw new StorageException(StorageException.Code.TOO_LARGE);
        StorageValidation.contentType(contentType);
        Map<String, String> safeMetadata = StorageValidation.metadata(metadata);
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(contentType).contentLength((long) content.length).metadata(safeMetadata);
        if (expectedETag == null) request.ifNoneMatch("*");
        else request.ifMatch(expectedETag);
        try {
            PutObjectResponse response = client(area).putObject(request.build(), RequestBody.fromBytes(content));
            return new ObjectMetadata(area, key, content.length, contentType, response.eTag(), null, safeMetadata);
        } catch (S3Exception e) {
            throw failure(e, true);
        } catch (SdkClientException e) {
            throw new StorageException(StorageException.Code.WRITE_UNCERTAIN);
        }
    }

    @Override
    public Optional<StoredObject> get(StorageArea area, String key) {
        String bucket = bucket(area);
        StorageValidation.key(key);
        try (ResponseInputStream<GetObjectResponse> input = client(area).getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).build())) {
            GetObjectResponse response = input.response();
            Long expectedLength = response.contentLength();
            if (expectedLength != null && expectedLength > maxObjectBytes) {
                input.abort();
                throw new StorageException(StorageException.Code.TOO_LARGE);
            }
            if (expectedLength != null && expectedLength < 0) {
                input.abort();
                throw new StorageException(StorageException.Code.UNAVAILABLE);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(expectedLength == null ? 8192
                    : (int) Math.min(expectedLength, 8192));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer, 0, Math.min(buffer.length, maxObjectBytes - bytes.size() + 1))) != -1) {
                if (bytes.size() + count > maxObjectBytes) {
                    input.abort();
                    throw new StorageException(StorageException.Code.TOO_LARGE);
                }
                bytes.write(buffer, 0, count);
            }
            if (expectedLength != null && expectedLength != bytes.size()) {
                throw new StorageException(StorageException.Code.UNAVAILABLE);
            }
            return Optional.of(new StoredObject(new ObjectMetadata(area, key, bytes.size(), response.contentType(),
                    response.eTag(), response.lastModified(), response.metadata()), bytes.toByteArray()));
        } catch (S3Exception e) {
            if (missingObject(e)) return Optional.empty();
            throw failure(e, false);
        } catch (SdkClientException | IOException e) {
            throw new StorageException(StorageException.Code.UNAVAILABLE);
        }
    }

    @Override
    public Optional<ObjectMetadata> head(StorageArea area, String key) {
        String bucket = bucket(area);
        StorageValidation.key(key);
        try {
            HeadObjectResponse response = client(area).headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new ObjectMetadata(area, key, response.contentLength(), response.contentType(),
                    response.eTag(), response.lastModified(), response.metadata()));
        } catch (S3Exception e) {
            if (missingObject(e)) return Optional.empty();
            throw failure(e, false);
        } catch (SdkClientException e) {
            throw new StorageException(StorageException.Code.UNAVAILABLE);
        }
    }

    @Override
    public void delete(StorageArea area, String key) {
        String bucket = bucket(area);
        StorageValidation.key(key);
        try {
            client(area).deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            if (!missingObject(e)) throw failure(e, true);
        } catch (SdkClientException e) {
            throw new StorageException(StorageException.Code.WRITE_UNCERTAIN);
        }
    }

    @Override
    public ObjectPage list(StorageArea area, String prefix, String continuationToken, int limit) {
        String bucket = bucket(area);
        StorageValidation.prefix(prefix);
        StorageValidation.token(continuationToken);
        StorageValidation.require(limit >= 1 && limit <= 1000);
        try {
            ListObjectsV2Response response = client(area).listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                    .prefix(prefix).continuationToken(continuationToken).maxKeys(limit).build());
            List<ObjectMetadata> entries = response.contents().stream().limit(limit)
                    .map(object -> new ObjectMetadata(area, object.key(), object.size(), null,
                            object.eTag(), object.lastModified(), Map.of())).toList();
            String token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
            if (Boolean.TRUE.equals(response.isTruncated()) && (token == null || token.isEmpty())) {
                throw new StorageException(StorageException.Code.UNAVAILABLE);
            }
            return new ObjectPage(entries, token);
        } catch (S3Exception e) {
            throw failure(e, false);
        } catch (SdkClientException e) {
            throw new StorageException(StorageException.Code.UNAVAILABLE);
        }
    }

    @Override
    public Optional<URI> publicUrl(StorageArea area, String key) {
        bucket(area);
        StorageValidation.key(key);
        return area == StorageArea.PRIVATE ? Optional.empty()
                : Optional.of(URI.create(publicBaseUrl + "/" + StorageValidation.encodedKey(key)));
    }

    private String bucket(StorageArea area) {
        StorageValidation.require(area != null);
        return area == StorageArea.PUBLIC ? publicBucket : privateBucket;
    }

    private S3Client client(StorageArea area) {
        return area == StorageArea.PUBLIC ? publicClient : privateClient;
    }

    private StorageException failure(S3Exception error, boolean write) {
        StorageException.Code code = switch (error.statusCode()) {
            case 404 -> StorageException.Code.NOT_FOUND;
            case 409, 412 -> StorageException.Code.CONFLICT;
            case 401, 403 -> StorageException.Code.ACCESS_DENIED;
            default -> write ? StorageException.Code.WRITE_UNCERTAIN : StorageException.Code.UNAVAILABLE;
        };
        return new StorageException(code);
    }

    private boolean missingObject(S3Exception error) {
        if (error.statusCode() != 404) return false;
        String code = error.awsErrorDetails() == null ? null : error.awsErrorDetails().errorCode();
        // HEAD may omit error details, but an explicit missing bucket is an infrastructure failure.
        return code == null || code.isBlank() || code.equals("NoSuchKey") || code.equals("NotFound");
    }
}
