package cc.ataglace.molebutter.storage.internal;

import cc.ataglace.molebutter.storage.api.StorageArea;
import cc.ataglace.molebutter.storage.api.StorageException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Exercises real SDK signing, request encoding and retry settings with an in-memory transport. */
class R2SdkTransportTest {
    @Test
    void signedCreateUsesR2PathStyleFixedLengthAndRequiredChecksumsOnly() {
        CapturingHttpClient transport = new CapturingHttpClient(200, new byte[0]);
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        try (S3Client client = R2StorageConfiguration.buildClient(properties, transport)) {
            R2ObjectStorage storage = new R2ObjectStorage(client, properties);
            storage.create(StorageArea.PUBLIC, "images/한 글.bin", new byte[]{0, (byte) 0xff, 1},
                    "application/octet-stream", Map.of());
            assertThat(transport.calls).isEqualTo(1);
            assertThat(transport.request.httpRequest().host()).isEqualTo("a".repeat(32) + ".r2.cloudflarestorage.com");
            assertThat(transport.request.httpRequest().encodedPath())
                    .isEqualTo("/molebutter/images/%ED%95%9C%20%EA%B8%80.bin");
            assertThat(header(transport.request, "If-None-Match")).containsExactly("*");
            assertThat(header(transport.request, "Content-Length")).containsExactly("3");
            assertThat(header(transport.request, "Authorization").getFirst()).contains("/auto/s3/aws4_request");
            assertThat(header(transport.request, "Content-Encoding")).doesNotContain("aws-chunked");
            assertThat(header(transport.request, "x-amz-content-sha256")).noneMatch(value -> value.startsWith("STREAMING-"));
            assertThat(header(transport.request, "x-amz-sdk-checksum-algorithm")).isEmpty();
            assertThat(header(transport.request, "x-amz-checksum-crc32")).isEmpty();
            assertThat(transport.body).containsExactly(0, (byte) 0xff, 1);
        }
    }

    @Test
    void sdkDoesNotRetryConditionalWriteOnServiceFailure() {
        byte[] response = "<Error><Code>InternalError</Code><Message>sensitive SDK response</Message></Error>"
                .getBytes(StandardCharsets.UTF_8);
        CapturingHttpClient transport = new CapturingHttpClient(503, response);
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        try (S3Client client = R2StorageConfiguration.buildClient(properties, transport)) {
            R2ObjectStorage storage = new R2ObjectStorage(client, properties);
            assertThatThrownBy(() -> storage.replace(StorageArea.PRIVATE, "files/a.bin", "\"v1\"", new byte[0],
                    "application/octet-stream", Map.of())).isInstanceOfSatisfying(StorageException.class, e -> {
                        assertThat(e.code()).isEqualTo(StorageException.Code.WRITE_UNCERTAIN);
                        assertThat(e.getMessage()).doesNotContain("sensitive SDK response");
                        assertThat(e.getCause()).isNull();
                    });
            assertThat(transport.calls).isEqualTo(1);
            assertThat(header(transport.request, "If-Match")).containsExactly("\"v1\"");
            assertThat(transport.request.httpRequest().encodedPath()).isEqualTo("/molebutter-private/files/a.bin");
        }
    }

    @Test
    void publicAndPrivateRequestsUseTheirBucketScopedCredentials() {
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        properties.getPublic().setAccessKeyId("public-test-access-key");
        properties.getPublic().setSecretAccessKey("public-test-secret-key");
        properties.getPublic().setBucket("public-test-bucket");
        properties.getPrivate().setAccessKeyId("private-test-access-key");
        properties.getPrivate().setSecretAccessKey("private-test-secret-key");
        properties.getPrivate().setBucket("private-test-bucket");
        CapturingHttpClient publicTransport = new CapturingHttpClient(200, new byte[0]);
        CapturingHttpClient privateTransport = new CapturingHttpClient(200, new byte[0]);
        try (S3Client publicClient = R2StorageConfiguration.buildClient(properties, publicTransport, StorageArea.PUBLIC);
                S3Client privateClient = R2StorageConfiguration.buildClient(properties, privateTransport, StorageArea.PRIVATE)) {
            R2ObjectStorage storage = new R2ObjectStorage(publicClient, privateClient, properties);
            storage.create(StorageArea.PUBLIC, "products/a.png", new byte[]{1}, "image/png", Map.of());
            storage.create(StorageArea.PRIVATE, "files/a.png", new byte[]{2}, "image/png", Map.of());
            assertThat(publicTransport.calls).isEqualTo(1);
            assertThat(privateTransport.calls).isEqualTo(1);
            assertThat(publicTransport.request.httpRequest().encodedPath()).isEqualTo("/public-test-bucket/products/a.png");
            assertThat(privateTransport.request.httpRequest().encodedPath()).isEqualTo("/private-test-bucket/files/a.png");
            assertThat(header(publicTransport.request, "Authorization").getFirst()).contains("Credential=public-test-access-key/")
                    .doesNotContain("private-test-access-key");
            assertThat(header(privateTransport.request, "Authorization").getFirst()).contains("Credential=private-test-access-key/")
                    .doesNotContain("public-test-access-key");
        }
    }

    private static List<String> header(HttpExecuteRequest request, String name) {
        return request.httpRequest().headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(List.of());
    }

    private static final class CapturingHttpClient implements SdkHttpClient {
        private final int status;
        private final byte[] response;
        private int calls;
        private HttpExecuteRequest request;
        private byte[] body;

        private CapturingHttpClient(int status, byte[] response) {
            this.status = status;
            this.response = response;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            this.request = request;
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    calls++;
                    body = request.contentStreamProvider().isPresent()
                            ? request.contentStreamProvider().orElseThrow().newStream().readAllBytes() : new byte[0];
                    return HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(status)
                                    .appendHeader("ETag", "\"v1\"").appendHeader("Content-Length", String.valueOf(response.length)).build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(response))).build();
                }

                @Override public void abort() { }
            };
        }

        @Override public void close() { }
    }
}
