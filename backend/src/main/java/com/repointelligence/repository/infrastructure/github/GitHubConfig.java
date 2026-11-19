package com.repointelligence.repository.infrastructure.github;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubProperties.class)
class GitHubConfig {

	@Bean
	@ConditionalOnMissingBean
	Sleeper sleeper() {
		return duration -> Thread.sleep(duration);
	}

}
