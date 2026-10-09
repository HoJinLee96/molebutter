package cc.ataglace.molebutter.storage.internal;

import cc.ataglace.molebutter.storage.api.ObjectMetadata;
import cc.ataglace.molebutter.storage.api.ObjectPage;
import cc.ataglace.molebutter.storage.api.StorageArea;
import cc.ataglace.molebutter.storage.api.StorageException;
import cc.ataglace.molebutter.storage.api.StoredObject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class R2ObjectStorageTest {
    private final S3Client client = mock(S3Client.class);
    private R2ObjectStorage storage;

    @BeforeEach
    void setUp() {
        R2ConfigurationProperties properties = properties();
        properties.setMaxObjectBytes(16);
        storage = new R2ObjectStorage(client, properties);
    }

    static R2ConfigurationProperties properties() {
        R2ConfigurationProperties properties = new R2ConfigurationProperties();
        properties.setEnabled(true);
        properties.setAccountId("a".repeat(32));
        properties.setAccessKeyId("test-access-key");
        properties.setSecretAccessKey("test-secret-key");
        return properties;
    }

    @Test
    void conditionalCreateAndReplaceRouteToSeparateBuckets() throws Exception {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"v1\"").build());
        byte[] content = new byte[]{0, (byte) 0xff, 1};
        ObjectMetadata result = storage.create(StorageArea.PUBLIC, "images/a.bin", content,
                "application/octet-stream", Map.of("owner", "123"));
        storage.replace(StorageArea.PRIVATE, "files/a.bin", result.eTag(), content,
                "application/octet-stream", Map.of());
        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(client, times(2)).putObject(requests.capture(), bodies.capture());
        assertThat(requests.getAllValues().getFirst().bucket()).isEqualTo("molebutter");
        assertThat(requests.getAllValues().getFirst().ifNoneMatch()).isEqualTo("*");
        assertThat(requests.getAllValues().getFirst().ifMatch()).isNull();
        assertThat(requests.getAllValues().getLast().bucket()).isEqualTo("molebutter-private");
        assertThat(requests.getAllValues().getLast().ifMatch()).isEqualTo("\"v1\"");
        assertThat(requests.getAllValues().getLast().ifNoneMatch()).isNull();
        assertThat(bodies.getAllValues().getFirst().contentStreamProvider().newStream().readAllBytes()).containsExactly(content);
        assertThat(result.size()).isEqualTo(content.length);
        assertThat(result.metadata()).containsEntry("owner", "123");
    }

    @Test
    void privateCrudAndListingNeverUseThePublicCredentialClient() {
        S3Client privateClient = mock(S3Client.class);
        R2ObjectStorage separated = new R2ObjectStorage(client, privateClient, properties());
        when(privateClient.getObject(any(GetObjectRequest.class))).thenReturn(stream(new byte[]{1}, 1L));
        when(privateClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1L).build());
        when(privateClient.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());
        when(privateClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"v1\"").build());
        separated.get(StorageArea.PRIVATE, "a.png");
        separated.head(StorageArea.PRIVATE, "a.png");
        separated.replace(StorageArea.PRIVATE, "a.png", "\"v1\"", new byte[]{1}, "image/png", Map.of());
        separated.list(StorageArea.PRIVATE, "", null, 1);
        separated.delete(StorageArea.PRIVATE, "a.png");
        verify(privateClient).getObject(any(GetObjectRequest.class));
        verify(privateClient).headObject(any(HeadObjectRequest.class));
        verify(privateClient).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(privateClient).listObjectsV2(any(ListObjectsV2Request.class));
        verify(privateClient).deleteObject(any(DeleteObjectRequest.class));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 412})
    void conditionsReturnConflictWithoutRetry(int status) {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(status).message("secret request data").build());
        assertCode(StorageException.Code.CONFLICT, () -> storage.create(StorageArea.PUBLIC, "a.bin", new byte[0],
                "application/octet-stream", Map.of()));
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void transportFailureHasUncertainWriteOutcomeAndNoSdkCause() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Authorization: sensitive-value"));
        assertThatThrownBy(() -> storage.replace(StorageArea.PRIVATE, "a.bin", "\"v1\"", new byte[0],
                "application/octet-stream", Map.of())).isInstanceOfSatisfying(StorageException.class, e -> {
                    assertThat(e.code()).isEqualTo(StorageException.Code.WRITE_UNCERTAIN);
                    assertThat(e.getMessage()).doesNotContain("Authorization", "sensitive-value", "a.bin");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void binaryGetIsBoundedAndDefensivelyCopied() {
        byte[] content = new byte[]{0, (byte) 0xff, 1, 2};
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(content, (long) content.length));
        StoredObject result = storage.get(StorageArea.PRIVATE, "a.bin").orElseThrow();
        assertThat(result.content()).containsExactly(content);
        byte[] copy = result.content();
        copy[0] = 9;
        assertThat(result.content()[0]).isZero();
        assertThat(result.metadata().size()).isEqualTo(content.length);
        verify(client).getObject(GetObjectRequest.builder().bucket("molebutter-private").key("a.bin").build());
    }

    @Test
    void oversizeDeclaredLengthAbortsBeforeReading() {
        ByteArrayInputStream source = spy(new ByteArrayInputStream(new byte[32]));
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(32L).build(), source);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
        assertCode(StorageException.Code.TOO_LARGE, () -> storage.get(StorageArea.PUBLIC, "a.bin"));
        verify(source, never()).read(any(byte[].class), anyInt(), anyInt());
    }

    @Test
    void absentOrFalseLengthCannotBypassStreamingLimit() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(new byte[17], null), stream(new byte[17], 1L));
        assertCode(StorageException.Code.TOO_LARGE, () -> storage.get(StorageArea.PUBLIC, "a.bin"));
        assertCode(StorageException.Code.TOO_LARGE, () -> storage.get(StorageArea.PUBLIC, "a.bin"));
    }

    @Test
    void truncatedReadDoesNotReturnIncompleteObject() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream(new byte[2], 3L));
        assertCode(StorageException.Code.UNAVAILABLE, () -> storage.get(StorageArea.PUBLIC, "a.bin"));
    }

    @Test
    void missingGetAndHeadAreEmptyAndDeleteIsIdempotent() {
        S3Exception missing = (S3Exception) S3Exception.builder().statusCode(404).build();
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(missing);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(missing);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(missing);
        assertThat(storage.get(StorageArea.PUBLIC, "missing.bin")).isEmpty();
        assertThat(storage.head(StorageArea.PRIVATE, "missing.bin")).isEmpty();
        assertThatCode(() -> storage.delete(StorageArea.PRIVATE, "missing.bin")).doesNotThrowAnyException();
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("molebutter-private").key("missing.bin").build());
    }

    @Test
    void missingBucketIsNotMistakenForMissingObject() {
        S3Exception missingBucket = (S3Exception) S3Exception.builder().statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build()).build();
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(missingBucket);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(missingBucket);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(missingBucket);
        assertCode(StorageException.Code.NOT_FOUND, () -> storage.get(StorageArea.PUBLIC, "a.bin"));
        assertCode(StorageException.Code.NOT_FOUND, () -> storage.head(StorageArea.PUBLIC, "a.bin"));
        assertCode(StorageException.Code.NOT_FOUND, () -> storage.delete(StorageArea.PUBLIC, "a.bin"));
    }

    @Test
    void headPreservesRemoteMetadata() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(8L).contentType("image/png").eTag("\"v2\"").metadata(Map.of("owner", "123")).build());
        ObjectMetadata result = storage.head(StorageArea.PUBLIC, "a.png").orElseThrow();
        assertThat(result.eTag()).isEqualTo("\"v2\"");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.metadata()).containsEntry("owner", "123");
    }

    @Test
    void oneListCallUsesLimitPrefixAndOpaqueContinuation() {
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("files/a.bin").size(3L).eTag("\"v1\"").build())
                .isTruncated(true).nextContinuationToken("opaque+/=").build(),
                ListObjectsV2Response.builder().isTruncated(false).build());
        ObjectPage page = storage.list(StorageArea.PRIVATE, "files/", null, 1);
        assertThat(page.objects()).hasSize(1);
        assertThat(page.objects().getFirst().area()).isEqualTo(StorageArea.PRIVATE);
        assertThat(page.nextContinuationToken()).isEqualTo("opaque+/=");
        assertThat(storage.list(StorageArea.PRIVATE, "files/", page.nextContinuationToken(), 1).nextContinuationToken()).isNull();
        verify(client).listObjectsV2(ListObjectsV2Request.builder().bucket("molebutter-private").prefix("files/").maxKeys(1).build());
        verify(client).listObjectsV2(ListObjectsV2Request.builder().bucket("molebutter-private").prefix("files/")
                .continuationToken("opaque+/=").maxKeys(1).build());
    }

    @Test
    void publicUrlEncodesEveryNonPathCharacterAndKeepsPrivateHidden() {
        assertThat(storage.publicUrl(StorageArea.PUBLIC, "images/한 글+?#%.png").orElseThrow().toASCIIString())
                .isEqualTo("https://assets.molebutter.link/images/%ED%95%9C%20%EA%B8%80%2B%3F%23%25.png");
        assertThat(storage.publicUrl(StorageArea.PRIVATE, "files/a.bin")).isEmpty();
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/a", "a/", "a//b", "a/../b", "./a", "a\\b", "a\nb", "a\u0000b", "\ud800"})
    void invalidKeysAreRejectedWithoutRequests(String key) {
        assertCode(StorageException.Code.INVALID_ARGUMENT, () -> storage.delete(StorageArea.PUBLIC, key));
        verifyNoInteractions(client);
    }

    @Test
    void invalidWriteMetadataAndWildcardETagAreRejectedWithoutRequests() {
        assertCode(StorageException.Code.TOO_LARGE, () -> storage.create(StorageArea.PUBLIC, "a.bin", new byte[17],
                "application/octet-stream", Map.of()));
        assertCode(StorageException.Code.INVALID_ARGUMENT, () -> storage.replace(StorageArea.PUBLIC, "a.bin", "*", new byte[0],
                "application/octet-stream", Map.of()));
        assertCode(StorageException.Code.INVALID_ARGUMENT, () -> storage.create(StorageArea.PUBLIC, "a.bin", new byte[0],
                "image/png\r\nX-Fake: true", Map.of()));
        assertCode(StorageException.Code.INVALID_ARGUMENT, () -> storage.create(StorageArea.PUBLIC, "a.bin", new byte[0],
                "image/png", Map.of("owner", "a\nb")));
        assertCode(StorageException.Code.INVALID_ARGUMENT, () -> storage.list(StorageArea.PUBLIC, "", null, 1001));
        verifyNoInteractions(client);
    }

    private static ResponseInputStream<GetObjectResponse> stream(byte[] content, Long length) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength(length).contentType("application/octet-stream")
                .eTag("\"v1\"").build(), new ByteArrayInputStream(content));
    }

    private static void assertCode(StorageException.Code code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
