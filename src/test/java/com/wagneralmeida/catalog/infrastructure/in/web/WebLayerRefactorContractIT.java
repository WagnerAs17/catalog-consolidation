package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.domain.Outcome;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.unit.DataSize;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class WebLayerRefactorContractIT {

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
    private DataSource dataSource;

    @Autowired
    private MeterRegistry registry;

    private Logger controllerLogger;
    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> controllerLogs;
    private ListAppender<ILoggingEvent> handlerLogs;

    @BeforeEach
    void captureLogs() {
        controllerLogger = (Logger) LoggerFactory.getLogger(ImportController.class);
        handlerLogger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        controllerLogs = appender(controllerLogger);
        handlerLogs = appender(handlerLogger);
    }

    @AfterEach
    void stopCapturingLogs() {
        controllerLogger.detachAppender(controllerLogs);
        handlerLogger.detachAppender(handlerLogs);
        controllerLogs.stop();
        handlerLogs.stop();
    }

    @Test
    void c18_emptyFileReturns400AndLeavesLogicalTablesUnchanged() throws Exception {
        LogicalTableState before = logicalTableState();

        mockMvc.perform(multipart("/imports").file(file(new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_UNREADABLE"));

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void c18_trailingGarbageReturns400AndLeavesLogicalTablesUnchanged() throws Exception {
        LogicalTableState before = logicalTableState();

        mockMvc.perform(multipart("/imports").file(jsonFile("[] trailing-garbage")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_UNREADABLE"));

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void c20_emptyArrayStillReturnsZeroTotals() throws Exception {
        mockMvc.perform(multipart("/imports").file(jsonFile("[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.records").value(0))
                .andExpect(jsonPath("$.totals.createdAndLinked").value(0))
                .andExpect(jsonPath("$.totals.linkedToExisting").value(0))
                .andExpect(jsonPath("$.totals.alreadyLinked").value(0))
                .andExpect(jsonPath("$.totals.notProcessed").value(0));
    }

    @Test
    void c18_413CarriesImportIdAndRecordsDurationWithoutCommittedRecords() throws Exception {
        LogicalTableState before = logicalTableState();
        long durationsBefore = registry.get("catalog.import.duration").timer().count();
        double recordsBefore = totalRecordCount();
        double rejectionsBefore = counter(
                "catalog.import.file.rejections", "code", "FILE_TOO_LARGE");

        mockMvc.perform(multipart("/imports").file(file(
                        new byte[(int) DataSize.ofMegabytes(5).toBytes() + 1])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

        assertThat(logicalTableState()).isEqualTo(before);
        assertThat(totalRecordCount()).isEqualTo(recordsBefore);
        assertThat(registry.get("catalog.import.duration").timer().count()).isEqualTo(durationsBefore + 1);
        assertThat(counter("catalog.import.file.rejections", "code", "FILE_TOO_LARGE"))
                .isEqualTo(rejectionsBefore + 1);
        assertCapturedImportId(handlerLogs);
        assertFileRejectionTagsAreUnchanged();
    }

    @Test
    void c18_415CarriesImportIdAndLeavesLogicalTablesUnchanged() throws Exception {
        LogicalTableState before = logicalTableState();

        mockMvc.perform(post("/imports").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertThat(logicalTableState()).isEqualTo(before);
        assertCapturedImportId(handlerLogs);
    }

    @Test
    void successfulResponseImportIdMatchesControllerLogMdcAndCountersAreVisibleAfterCommit() throws Exception {
        double createdBefore = counter(
                "catalog.import.records", "outcome", Outcome.CREATED_AND_LINKED.name());
        MvcResult result = mockMvc.perform(multipart("/imports").file(jsonFile("""
                [{"Id":"refactor-1","SellerName":"Refactor Seller","Name":"Refactor Product"}]
                """)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String importId = response.required("importId").textValue();
        assertThat(controllerLogs.list)
                .extracting(event -> event.getMDCPropertyMap().get("importId"))
                .contains(importId);
        assertThat(counter("catalog.import.records", "outcome", Outcome.CREATED_AND_LINKED.name()))
                .isEqualTo(createdBefore + 1);
    }

    private void assertCapturedImportId(ListAppender<ILoggingEvent> logs) {
        assertThat(logs.list)
                .extracting(event -> event.getMDCPropertyMap().get("importId"))
                .allSatisfy(importId -> assertThat(importId).isNotBlank());
        assertThat(logs.list).isNotEmpty();
    }

    private void assertFileRejectionTagsAreUnchanged() {
        Set<String> tags = registry.find("catalog.import.file.rejections").counters().stream()
                .map(counter -> counter.getId().getTag("code"))
                .collect(Collectors.toSet());
        assertThat(tags).containsExactlyInAnyOrder(
                "FILE_UNREADABLE",
                "IMPORT_IN_PROGRESS",
                "FILE_TOO_LARGE",
                "UNSUPPORTED_MEDIA_TYPE",
                "NOT_A_JSON_ARRAY",
                "TOO_MANY_RECORDS");
    }

    private double totalRecordCount() {
        return registry.find("catalog.import.records").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private ListAppender<ILoggingEvent> appender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private LogicalTableState logicalTableState() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return new LogicalTableState(
                    rows(connection, "SELECT Id, Name, Brand, Category, NormalizedName FROM Product ORDER BY Id"),
                    rows(connection, "SELECT Id, SellerName, SellerProductId, ProductId FROM SellerProduct ORDER BY Id"));
        }
    }

    private List<List<String>> rows(Connection connection, String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> row = new ArrayList<>(columns);
                for (int column = 1; column <= columns; column++) {
                    String value = result.getString(column);
                    row.add(value == null ? "<NULL>" : value);
                }
                rows.add(List.copyOf(row));
            }
        }
        return List.copyOf(rows);
    }

    private MockMultipartFile jsonFile(String json) {
        return file(json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "products.json", "application/json", bytes);
    }

    private record LogicalTableState(List<List<String>> products, List<List<String>> sellerProducts) {
    }
}
