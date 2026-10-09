package cc.ataglace.molebutter.imaging.internal.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration(proxyBeanMethods = false)
public class ImagingJobConfig {
    @Bean(name = "imagingDownloadJobExecutor")
    public ThreadPoolTaskExecutor imagingDownloadJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(2); executor.setQueueCapacity(6);
        executor.setThreadNamePrefix("imaging-download-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
