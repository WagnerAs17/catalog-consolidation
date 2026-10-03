package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wagneralmeida.catalog.CatalogApplication;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportMaliciousTextIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Test
    void c14_maliciousTextThroughFullFlowKeepsTablesIntact() throws Exception {
        String maliciousText = "x'; DROP TABLE Product; --";
        String json = "[{\"Id\":\"attack-1\",\"SellerName\":\"Acme\",\"Name\":\""
                + maliciousText + "\"}]";

        mockMvc.perform(multipart("/imports").file(new MockMultipartFile(
                        "file", "products.json", "application/json", json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.records").value(1))
                .andExpect(jsonPath("$.totals.createdAndLinked").value(1))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED_AND_LINKED"));

        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableNames(connection)).contains("Product", "SellerProduct");
            assertThat(singleString(connection, "SELECT Name FROM Product WHERE Id = 1"))
                    .isEqualTo(maliciousText);
            assertThat(singleLong(connection, "SELECT COUNT(*) FROM SellerProduct")).isOne();
            assertThat(columnNames(connection, "Product")).doesNotContain("importId");
            assertThat(columnNames(connection, "SellerProduct")).doesNotContain("importId");
        }
    }

    private List<String> tableNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private List<String> columnNames(Connection connection, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                names.add(result.getString("name"));
            }
        }
        return names;
    }

    private String singleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }

    private long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.getLong(1);
        }
    }
}
