package com.axion11.visualops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }

    /** Thread pool for parallel image upload processing within a batch. */
    @Bean(name = "imageUploadExecutor")
    public ExecutorService imageUploadExecutor() {
        return Executors.newFixedThreadPool(6);
    }

    /**
     * Pool for Phase 2 deferred AI analysis (Vision + Gemini). Smaller than the upload
     * pool because each task holds a full image in memory while waiting on remote calls.
     */
    @Bean(name = "aiAnalysisExecutor")
    public ExecutorService aiAnalysisExecutor() {
        return Executors.newFixedThreadPool(3);
    }
}
