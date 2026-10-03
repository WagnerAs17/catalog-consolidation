package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportResponseContractTest {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Logger controllerLogger;
    private ListAppender<ILoggingEvent> controllerLogs;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM SellerProduct");
        jdbcTemplate.update("DELETE FROM Product");
        jdbcTemplate.update("DELETE FROM sqlite_sequence WHERE name IN ('Product', 'SellerProduct')");
        jdbcTemplate.update(
                "INSERT INTO Product (Name, Brand, Category, NormalizedName) VALUES (?, ?, ?, ?)",
                "Existing", "Acme", "Catalog", "existing");
        Long productId = jdbcTemplate.queryForObject(
                "SELECT Id FROM Product WHERE NormalizedName = ?", Long.class, "existing");
        jdbcTemplate.update(
                "INSERT INTO SellerProduct (SellerName, SellerProductId, ProductId) VALUES (?, ?, ?)",
                "Existing Seller", "3", productId);

        controllerLogger = (Logger) LoggerFactory.getLogger(ImportController.class);
        controllerLogs = new ListAppender<>();
        controllerLogs.start();
        controllerLogger.addAppender(controllerLogs);
    }

    @AfterEach
    void stopCapturingLogs() {
        controllerLogger.detachAppender(controllerLogs);
        controllerLogs.stop();
    }

    @Test
    void c01_c02_c12_c13_responseSerializationAndCorrelationRules() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/imports").file(jsonFile("""
                [
                  {"Id":"1","SellerName":"New Seller","Name":"New Product"},
                  {"Id":"2","SellerName":"Other","Name":"Existing","Brand":"Acme","Category":"Different"},
                  {"Id":"3","SellerName":"Existing Seller","Name":"Existing","Brand":"Acme"},
                  false
                ]
                """)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        UUID responseImportId = UUID.fromString(body.required("importId").textValue());
        assertThat(controllerLogs.list)
                .extracting(event -> event.getMDCPropertyMap().get("importId"))
                .contains(responseImportId.toString());
        assertThat(MDC.get("importId")).isNull();

        assertThat(body.required("totals").required("records").intValue()).isEqualTo(4);
        assertThat(body.required("totals").required("createdAndLinked").intValue()).isEqualTo(1);
        assertThat(body.required("totals").required("linkedToExisting").intValue()).isEqualTo(1);
        assertThat(body.required("totals").required("alreadyLinked").intValue()).isEqualTo(1);
        assertThat(body.required("totals").required("notProcessed").intValue()).isEqualTo(1);

        JsonNode processed = body.required("results").get(0);
        assertThat(processed.required("position").intValue()).isOne();
        assertThat(processed.has("sellerName")).isTrue();
        assertThat(processed.has("sellerProductId")).isTrue();
        assertThat(processed.has("productId")).isTrue();
        assertThat(processed.required("warnings").isArray()).isTrue();
        assertThat(processed.has("reason")).isFalse();
        assertThat(processed.has("detail")).isFalse();

        JsonNode divergent = body.required("results").get(1);
        assertThat(divergent.required("warnings").get(0).textValue()).isEqualTo("CATEGORY_DIVERGENCE");
        assertThat(divergent.has("reason")).isFalse();
        assertThat(divergent.has("detail")).isFalse();

        JsonNode notProcessed = body.required("results").get(3);
        assertThat(notProcessed.required("position").intValue()).isEqualTo(4);
        assertThat(notProcessed.has("sellerName")).isTrue();
        assertThat(notProcessed.get("sellerName").isNull()).isTrue();
        assertThat(notProcessed.has("sellerProductId")).isTrue();
        assertThat(notProcessed.get("sellerProductId").isNull()).isTrue();
        assertThat(notProcessed.has("productId")).isTrue();
        assertThat(notProcessed.get("productId").isNull()).isTrue();
        assertThat(notProcessed.required("reason").textValue()).isEqualTo("INVALID_RECORD");
        assertThat(notProcessed.required("detail").textValue()).isEqualTo("NOT_AN_OBJECT");
        assertThat(notProcessed.required("warnings").isArray()).isTrue();
        assertThat(notProcessed.required("warnings").isEmpty()).isTrue();
    }

    private MockMultipartFile jsonFile(String json) {
        return new MockMultipartFile(
                "file", "products.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }
}
