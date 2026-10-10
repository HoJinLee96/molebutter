package cc.ataglace.molebutter.marketplace.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Slow external reads never occupy the scheduler used by inventory or notifications. */
@Configuration(proxyBeanMethods=false)
class MarketplaceOrderScheduling {
    // Defining a dedicated scheduler makes Boot's default scheduler back off. Keep the
    // conventional name so other @Scheduled jobs continue using a separate executor.
    @Bean(name="taskScheduler")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(){
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
    @Bean(name="marketplaceOrderScheduler")
    ThreadPoolTaskScheduler marketplaceOrderScheduler(){
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("marketplace-orders-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
