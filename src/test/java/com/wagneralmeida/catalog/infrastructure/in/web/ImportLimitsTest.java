package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportLimitsTest {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ImportProductsUseCase useCase;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void defaultLimitsAreFiveMegabytesAndFiveThousandRecords() {
        assertThat(environment.getProperty("catalog.import.max-body-size")).isEqualTo("5MB");
        assertThat(environment.getProperty("catalog.import.max-records", Integer.class)).isEqualTo(5_000);
        assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("5MB");
        assertThat(environment.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("5MB");
    }

    @Test
    void c18_configuredLimitsRejectWholeFileWithoutWrites() throws Exception {
        LogicalTableState beforeBodyRejection = logicalTableState();
        byte[] tooLarge = new byte[(int) DataSize.ofMegabytes(5).toBytes() + 1];

        mockMvc.perform(multipart("/imports").file(file(tooLarge)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        assertThat(logicalTableState()).isEqualTo(beforeBodyRejection);

        LogicalTableState beforeRecordRejection = logicalTableState();
        String tooManyRecords = "[" + "{},".repeat(5_000) + "{}]";
        mockMvc.perform(multipart("/imports").file(jsonFile(tooManyRecords)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TOO_MANY_RECORDS"));
        assertThat(logicalTableState()).isEqualTo(beforeRecordRejection);
    }

    @Test
    void overriddenLimitsAreEnforcedBeforeTheUseCase() throws Exception {
        ImportMetrics metrics = new ImportMetrics(new SimpleMeterRegistry());
        ImportFileParser parser = new ImportFileParser(
                objectMapper, new ImportRecordMapper(), DataSize.ofBytes(100), 2);
        ImportController controller = new ImportController(useCase, parser, metrics);
        MockMvc overridden = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(metrics))
                .addFilters(new ImportIdFilter())
                .build();
        LogicalTableState before = logicalTableState();

        overridden.perform(multipart("/imports").file(file(new byte[101])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        overridden.perform(multipart("/imports").file(jsonFile("[{},{},{}]")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TOO_MANY_RECORDS"));

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void veryLongFieldWithinBodyLimitIsProcessedNormally() throws Exception {
        String longName = "a".repeat(100 * 1_024);
        String json = "[{\"Id\":\"long-1\",\"SellerName\":\"Acme\",\"Name\":\"" + longName + "\"}]";

        mockMvc.perform(multipart("/imports").file(jsonFile(json)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.records").value(1))
                .andExpect(jsonPath("$.totals.createdAndLinked").value(1));
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
