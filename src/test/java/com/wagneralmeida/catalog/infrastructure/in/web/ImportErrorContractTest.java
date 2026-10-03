package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.application.service.ImportLock;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductRepositoryAdapter;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.unit.DataSize;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportErrorContractTest {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();
    private static final String INTERNAL_MESSAGE = "sensitive-database-detail";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ImportLock importLock;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean(name = "productRepository")
    private ProductRepositoryAdapter products;

    @Test
    void c18_missingFilePartReturns400AndChangesNothing() throws Exception {
        LogicalTableState before = logicalTableState();

        assertProblem(mockMvc.perform(multipart("/imports")), 400, "FILE_UNREADABLE");

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void c18_invalidUtf8Returns400AndChangesNothing() throws Exception {
        LogicalTableState before = logicalTableState();
        MockMultipartFile invalidUtf8 = file(new byte[] {(byte) 0xC3, 0x28});

        assertProblem(mockMvc.perform(multipart("/imports").file(invalidUtf8)), 400, "FILE_UNREADABLE");

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void c18_invalidJsonReturns400AndChangesNothing() throws Exception {
        LogicalTableState before = logicalTableState();

        assertProblem(mockMvc.perform(multipart("/imports").file(jsonFile("[{payload-secret"))),
                400, "FILE_UNREADABLE");

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void c18_notJsonArrayReturns422AndChangesNothing() throws Exception {
        LogicalTableState before = logicalTableState();

        assertProblem(mockMvc.perform(multipart("/imports").file(jsonFile("{\"Name\":\"secret\"}"))),
                422, "NOT_A_JSON_ARRAY");

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void c18_bodyOverLimitReturns413AndChangesNothing() throws Exception {
        LogicalTableState before = logicalTableState();
        byte[] bytes = new byte[(int) DataSize.ofMegabytes(5).toBytes() + 1];

        assertProblem(mockMvc.perform(multipart("/imports").file(file(bytes))),
                413, "FILE_TOO_LARGE");

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void c18_tooManyRecordsReturns422AndChangesNothing() throws Exception {
        LogicalTableState before = logicalTableState();
        String json = "[" + "{},".repeat(5_000) + "{}]";

        assertProblem(mockMvc.perform(multipart("/imports").file(jsonFile(json))),
                422, "TOO_MANY_RECORDS");

        assertThat(logicalTableState()).isEqualTo(before);
    }

    @Test
    void wrongRequestContentTypeReturns415() throws Exception {
        assertProblem(mockMvc.perform(post("/imports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]")),
                415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void concurrentRequestReturns409() throws Exception {
        assertThat(importLock.tryAcquire()).isTrue();
        try {
            assertProblem(mockMvc.perform(multipart("/imports").file(jsonFile("[]"))),
                    409, "IMPORT_IN_PROGRESS");
        } finally {
            importLock.release();
        }
    }

    @Test
    void unexpectedFailureReturnsGeneric500() throws Exception {
        LogicalTableState before = logicalTableState();
        doThrow(new ForcedInternalFailure(INTERNAL_MESSAGE))
                .when(products).findByNormalizedName(anyString());

        assertProblem(mockMvc.perform(multipart("/imports").file(jsonFile("""
                        [{"Id":"failure","SellerName":"Acme","Name":"Failure"}]
                        """))),
                500, "INTERNAL_ERROR")
                .andExpect(content().string(not(containsString(INTERNAL_MESSAGE))));

        assertThat(logicalTableState()).isEqualTo(before);
    }

    private ResultActions assertProblem(ResultActions result, int expectedStatus, String expectedCode)
            throws Exception {
        return result
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
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

    private static final class ForcedInternalFailure extends RuntimeException {

        private ForcedInternalFailure(String message) {
            super(message);
        }
    }
}
