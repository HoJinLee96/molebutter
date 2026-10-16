package cc.ataglace.molebutter.coupang.internal.orders;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Slow external reads never occupy the scheduler used by inventory or notifications. */
@Configuration(proxyBeanMethods=false)
class CoupangOrderScheduling {
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
    @Bean(name="coupangOrderScheduler")
    ThreadPoolTaskScheduler coupangOrderScheduler(){
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("coupang-orders-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
