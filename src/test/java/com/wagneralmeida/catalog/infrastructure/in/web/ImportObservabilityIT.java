package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductRepositoryAdapter;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportObservabilityIT {

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
    private Environment environment;

    @MockitoSpyBean(name = "productRepository")
    private ProductRepositoryAdapter products;

    private Logger controllerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureControllerLogs() {
        controllerLogger = (Logger) LoggerFactory.getLogger(ImportController.class);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingControllerLogs() {
        controllerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void healthAndMetricsAreTheOnlyManagementSurfaceAndHealthDoesNotWaitForWriter() throws Exception {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,metrics");
        assertThat(environment.getProperty("management.health.db.enabled", Boolean.class)).isFalse();

        LookupGate gate = holdNextLookup();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> importRequest = executor.submit(() -> mockMvc.perform(
                            multipart("/imports").file(jsonFile("health")))
                    .andReturn());
            assertThat(gate.entered().await(5, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> healthRequest = executor.submit(() -> mockMvc.perform(get("/actuator/health"))
                    .andReturn());
            MvcResult health = healthRequest.get(2, TimeUnit.SECONDS);
            assertThat(health.getResponse().getStatus()).isEqualTo(200);

            mockMvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
            MvcResult actuator = mockMvc.perform(get("/actuator")).andExpect(status().isOk()).andReturn();
            assertThat(actuator.getResponse().getContentAsString())
                    .contains("/actuator/health", "/actuator/metrics")
                    .doesNotContain("/actuator/env", "/actuator/beans");

            gate.release().countDown();
            assertThat(importRequest.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            gate.release().countDown();
        }
    }

    @Test
    void responseImportIdMatchesGenericLifecycleLogMdcAndMdcIsCleared() throws Exception {
        String marker = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(multipart("/imports").file(jsonFile(marker)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String importId = response.required("importId").textValue();
        assertThat(UUID.fromString(importId)).isNotNull();
        assertThat(MDC.get("importId")).isNull();

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(event -> event.getMDCPropertyMap().get("importId"))
                .contains(importId);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(marker, "Sensitive Seller", "secret-code"));
    }

    private MockMultipartFile jsonFile(String suffix) {
        String json = "[{\"Id\":\"secret-code-" + suffix
                + "\",\"SellerName\":\"Sensitive Seller\",\"Name\":\"Sensitive Product "
                + suffix + "\"}]";
        return new MockMultipartFile(
                "file", "products.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private LookupGate holdNextLookup() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release held import");
                }
            }
            return invocation.callRealMethod();
        }).when(products).findByNormalizedName(anyString());
        return new LookupGate(entered, release);
    }

    private record LookupGate(CountDownLatch entered, CountDownLatch release) {
    }
}
