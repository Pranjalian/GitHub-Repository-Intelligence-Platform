package com.repointelligence.analysis.application;

import com.repointelligence.analysis.spi.AnalyzerSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyzerSettings.class)
class AnalysisConfig {

}
