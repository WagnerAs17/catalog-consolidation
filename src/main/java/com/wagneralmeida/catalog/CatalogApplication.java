package com.wagneralmeida.catalog;

import com.wagneralmeida.catalog.infrastructure.in.web.ImportMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }

    @Bean
    ImportMetrics importMetrics(MeterRegistry registry) {
        return new ImportMetrics(registry);
    }
}
