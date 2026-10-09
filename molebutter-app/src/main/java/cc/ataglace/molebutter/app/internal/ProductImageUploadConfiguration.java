package cc.ataglace.molebutter.app.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class ProductImageUploadConfiguration {
    @Bean("productImageUploadExecutor")
    ThreadPoolTaskExecutor productImageUploadExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("product-image-upload-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
