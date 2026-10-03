package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.domain.Outcome;
import com.wagneralmeida.catalog.domain.RejectionReason;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportMetricsRegistrationTest {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Test
    void allCatalogImportInstrumentsAndTagValuesExistAtZeroOnStartup() throws Exception {
        for (String name : Set.of(
                "catalog.import.records",
                "catalog.import.rejections",
                "catalog.import.file.rejections",
                "catalog.import.duration",
                "catalog.import.failures")) {
            mockMvc.perform(get("/actuator/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.names").isArray())
                    .andExpect(jsonPath("$.names").value(org.hamcrest.Matchers.hasItem(name)));
        }

        assertZeroCountersWithAllTags(
                "catalog.import.records",
                "outcome",
                Arrays.stream(Outcome.values()).map(Enum::name).collect(Collectors.toSet()));
        assertZeroCountersWithAllTags(
                "catalog.import.rejections",
                "reason",
                Arrays.stream(RejectionReason.values()).map(Enum::name).collect(Collectors.toSet()));
        assertZeroCountersWithAllTags(
                "catalog.import.file.rejections",
                "code",
                Set.of(
                        "FILE_UNREADABLE",
                        "IMPORT_IN_PROGRESS",
                        "FILE_TOO_LARGE",
                        "UNSUPPORTED_MEDIA_TYPE",
                        "NOT_A_JSON_ARRAY",
                        "TOO_MANY_RECORDS"));
        assertThat(registry.find("catalog.import.failures").counter()).isNotNull();
        assertThat(registry.find("catalog.import.failures").counter().count()).isZero();
        assertThat(registry.find("catalog.import.duration").timer()).isNotNull();
        assertThat(registry.find("catalog.import.duration").timer().count()).isZero();
    }

    private void assertZeroCountersWithAllTags(
            String metricName, String tagName, Set<String> expectedTagValues) {
        Collection<Counter> counters = registry.find(metricName).counters();
        assertThat(counters)
                .allSatisfy(counter -> assertThat(counter.count()).isZero())
                .extracting(counter -> counter.getId().getTag(tagName))
                .containsExactlyInAnyOrderElementsOf(expectedTagValues);
    }
}
