package com.ocr.interfaces.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties({OllamaProperties.class, OcrProperties.class})
public class AppConfig {

    @Bean
    WebClient ollamaWebClient(OllamaProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    @Bean(name = "ocrTaskExecutor")
    Executor ocrTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ocr-");
        executor.initialize();
        return executor;
    }
}
