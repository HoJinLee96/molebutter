package cc.ataglace.molebutter.storage.internal;

import cc.ataglace.molebutter.storage.api.ObjectStorage;
import cc.ataglace.molebutter.storage.api.StorageArea;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(R2ConfigurationProperties.class)
public class R2StorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "cloudflare.r2", name = "enabled", havingValue = "false", matchIfMissing = true)
    ObjectStorage disabledObjectStorage() {
        return new DisabledObjectStorage();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cloudflare.r2", name = "enabled", havingValue = "true")
    static class EnabledR2StorageConfiguration {
        @Bean(destroyMethod = "close")
        SdkHttpClient r2HttpClient(R2ConfigurationProperties properties) {
            properties.validatedEndpoint();
            return UrlConnectionHttpClient.builder()
                    .connectionTimeout(properties.getConnectTimeout())
                    .socketTimeout(properties.getRequestTimeout()).build();
        }

        @Bean(destroyMethod = "close")
        S3Client r2PublicS3Client(R2ConfigurationProperties properties, SdkHttpClient r2HttpClient) {
            return buildClient(properties, r2HttpClient, StorageArea.PUBLIC);
        }

        @Bean(destroyMethod = "close")
        S3Client r2PrivateS3Client(R2ConfigurationProperties properties, SdkHttpClient r2HttpClient) {
            return buildClient(properties, r2HttpClient, StorageArea.PRIVATE);
        }

        @Bean
        ObjectStorage r2ObjectStorage(@Qualifier("r2PublicS3Client") S3Client publicClient,
                @Qualifier("r2PrivateS3Client") S3Client privateClient, R2ConfigurationProperties properties) {
            return new R2ObjectStorage(publicClient, privateClient, properties);
        }
    }

    /** Explicit transport permits request-level tests without network access. */
    static S3Client buildClient(R2ConfigurationProperties properties, SdkHttpClient httpClient) {
        return buildClient(properties, httpClient, StorageArea.PUBLIC);
    }

    static S3Client buildClient(R2ConfigurationProperties properties, SdkHttpClient httpClient, StorageArea area) {
        return S3Client.builder()
                .endpointOverride(properties.validatedEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.accessKeyId(area), properties.secretAccessKey(area))))
                .region(Region.of("auto"))
                .httpClient(httpClient)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(properties.getRequestTimeout())
                        .apiCallAttemptTimeout(properties.getRequestTimeout())
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).build())
                .build();
    }
}
