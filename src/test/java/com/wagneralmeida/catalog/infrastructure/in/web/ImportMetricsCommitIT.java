package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.domain.Outcome;
import com.wagneralmeida.catalog.domain.RejectionReason;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductRepositoryAdapter;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportMetricsCommitIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry registry;

    @MockitoSpyBean(name = "productRepository")
    private ProductRepositoryAdapter products;

    @Test
    void c16_committedMetricsMatchReportAndRolledBackFileCountsNothing() throws Exception {
        MvcResult successful = mockMvc.perform(multipart("/imports").file(jsonFile("""
                [
                  {"Id":"1","SellerName":"Acme","Name":"Camera"},
                  {},
                  {"Id":"2","SellerName":"Other","Name":"Camera"},
                  {"Id":"1","SellerName":"Acme","Name":"Camera"}
                ]
                """)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode report = objectMapper.readTree(successful.getResponse().getContentAsByteArray());
        assertCommittedCountersMatch(report);
        Map<Outcome, Double> recordsAfterCommit = recordCounters();
        Map<RejectionReason, Double> rejectionsAfterCommit = rejectionCounters();

        AtomicInteger createCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (createCalls.incrementAndGet() == 2) {
                throw new ForcedRepositoryFailure();
            }
            return invocation.callRealMethod();
        }).when(products).create(org.mockito.ArgumentMatchers.any());
        mockMvc.perform(multipart("/imports").file(jsonFile("""
                [
                  {"Id":"failed-1","SellerName":"Acme","Name":"Failed One"},
                  {"Id":"failed-2","SellerName":"Acme","Name":"Failed Two"}
                ]
                """)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(recordCounters()).isEqualTo(recordsAfterCommit);
        assertThat(rejectionCounters()).isEqualTo(rejectionsAfterCommit);
        assertThat(counter("catalog.import.failures")).isEqualTo(1.0);

        mockMvc.perform(multipart("/imports"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_UNREADABLE"));
        assertThat(counter("catalog.import.file.rejections", "code", "FILE_UNREADABLE"))
                .isEqualTo(1.0);
        assertThat(registry.get("catalog.import.duration").timer().count()).isGreaterThanOrEqualTo(2);

        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("catalog.import."))
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .doesNotContain("seller", "sellerName", "sellerProductId");
    }

    private void assertCommittedCountersMatch(JsonNode report) {
        Map<Outcome, Long> expectedOutcomes = new EnumMap<>(Outcome.class);
        Map<RejectionReason, Long> expectedRejections = new EnumMap<>(RejectionReason.class);
        for (Outcome outcome : Outcome.values()) {
            expectedOutcomes.put(outcome, 0L);
        }
        for (RejectionReason reason : RejectionReason.values()) {
            expectedRejections.put(reason, 0L);
        }
        report.required("results").forEach(result -> {
            Outcome outcome = Outcome.valueOf(result.required("outcome").textValue());
            expectedOutcomes.compute(outcome, (ignored, count) -> count + 1);
            if (outcome == Outcome.NOT_PROCESSED) {
                RejectionReason reason = RejectionReason.valueOf(result.required("reason").textValue());
                expectedRejections.compute(reason, (ignored, count) -> count + 1);
            }
        });

        expectedOutcomes.forEach((outcome, count) -> assertThat(
                        counter("catalog.import.records", "outcome", outcome.name()))
                .isEqualTo(count.doubleValue()));
        expectedRejections.forEach((reason, count) -> assertThat(
                        counter("catalog.import.rejections", "reason", reason.name()))
                .isEqualTo(count.doubleValue()));
    }

    private Map<Outcome, Double> recordCounters() {
        Map<Outcome, Double> counters = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, counter("catalog.import.records", "outcome", outcome.name()));
        }
        return counters;
    }

    private Map<RejectionReason, Double> rejectionCounters() {
        Map<RejectionReason, Double> counters = new EnumMap<>(RejectionReason.class);
        for (RejectionReason reason : RejectionReason.values()) {
            counters.put(reason, counter("catalog.import.rejections", "reason", reason.name()));
        }
        return counters;
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private MockMultipartFile jsonFile(String json) {
        return new MockMultipartFile(
                "file", "products.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    static final class ForcedRepositoryFailure extends RuntimeException {
    }
}
