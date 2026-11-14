package com.ebank.common.scheduling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * One scheduler for every {@code @Scheduled} job (outbox relay, cleanups, WebSocket expiry).
 *
 * <p>Declared by hand because the WebSocket message broker registers its own TaskScheduler,
 * which makes Spring Boot skip its default one. Without this bean, jobs that block on the
 * database would run on the broker's heartbeat threads. {@code @Scheduled} picks the bean named
 * {@code taskScheduler} when several exist.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {

    private static final int POOL_SIZE = 4;

    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("ebank-scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
