package com.repointelligence.analysis.orchestration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Two separate bounded executors. Jobs run on {@code analysisJobExecutor}; each analyzer task runs on
 * {@code analyzerExecutor}. Keeping them apart means a job that waits for its analyzers can never
 * starve the pool those analyzers need (no nested-future deadlock).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties({ AnalysisJobProperties.class, AnalysisResultProperties.class })
class AnalysisExecutionConfig {

	@Bean(name = "analysisJobExecutor")
	ThreadPoolTaskExecutor analysisJobExecutor(AnalysisJobProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("analysis-job-");
		executor.setCorePoolSize(properties.maxConcurrentJobs());
		executor.setMaxPoolSize(properties.maxConcurrentJobs());
		executor.setQueueCapacity(properties.queueCapacity());
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

	/** One virtual thread per analyzer task: cheap, and an analyzer that blocks cannot block another. */
	@Bean(name = "analyzerExecutor", destroyMethod = "shutdownNow")
	ExecutorService analyzerExecutor() {
		return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("analyzer-", 0).factory());
	}

}
