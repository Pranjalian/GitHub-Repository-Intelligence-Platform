package com.repointelligence.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * RAG wiring. Index jobs run on their own single thread, apart from the analysis pools, so indexing can
 * never delay an analysis and two jobs never index at the same time.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

	@Bean(name = "ragIndexExecutor")
	ThreadPoolTaskExecutor ragIndexExecutor(RagProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("rag-index-");
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(properties.indexing().queueCapacity());
		executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

}
