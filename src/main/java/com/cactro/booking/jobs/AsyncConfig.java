package com.cactro.booking.jobs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

/** The background job queue: a bounded worker pool fed by an in-memory queue. */
@Configuration
public class AsyncConfig {
    @Bean(name = "jobExecutor")
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(5);          // = number of SMTP connections we use in parallel
        ex.setMaxPoolSize(5);
        ex.setQueueCapacity(100_000);   // absorbs booking bursts
        ex.setThreadNamePrefix("job-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.initialize();
        return ex;
    }
}
