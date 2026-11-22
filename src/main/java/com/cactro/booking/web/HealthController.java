package com.cactro.booking.web;

import com.cactro.booking.jobs.NotificationJobs;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
public class HealthController {
    private final ThreadPoolTaskExecutor executor;
    private final NotificationJobs jobs;

    public HealthController(@Qualifier("jobExecutor") Executor executor, NotificationJobs jobs) {
        this.executor = (ThreadPoolTaskExecutor) executor;
        this.jobs = jobs;
    }

    @GetMapping({"/", "/health"})
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "Event Booking API",
                "jobs", Map.of("queued", executor.getThreadPoolExecutor().getQueue().size(),
                        "running", executor.getActiveCount(),
                        "processed", jobs.processedCount(), "failed", jobs.failedCount()));
    }
}
