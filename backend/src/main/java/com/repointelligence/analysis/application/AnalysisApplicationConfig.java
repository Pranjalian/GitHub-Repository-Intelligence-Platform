package com.repointelligence.analysis.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AnalysisApplicationConfig {

	/** Placeholder until the analyzer registry exists (Step 1.6): no analyzers are registered yet. */
	@Bean
	@ConditionalOnMissingBean
	AnalyzerSetHashProvider analyzerSetHashProvider() {
		return () -> "no-analyzers";
	}

}
