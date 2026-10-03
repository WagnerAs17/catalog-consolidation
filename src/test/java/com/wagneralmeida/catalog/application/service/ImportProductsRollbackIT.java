package com.wagneralmeida.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductRepositoryAdapter;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(classes = CatalogApplication.class)
class ImportProductsRollbackIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private ImportProductsUseCase useCase;

    @MockitoSpyBean(name = "productRepository")
    private ProductRepositoryAdapter products;

    @Autowired
    private DataSource dataSource;

    @Test
    void c16_systemFailureRollsBackLogicalTableState() throws SQLException {
        LogicalTableState before = logicalTableState();
        AtomicInteger createCalls = new AtomicInteger();
        doAnswer(invocation -> {
                    if (createCalls.incrementAndGet() == 2) {
                        throw new ForcedRepositoryFailure();
                    }
                    return invocation.callRealMethod();
                })
                .when(products)
                .create(any());

        assertThatThrownBy(() -> useCase.importProducts(List.of(
                record(1, "sku-1", "Acme", "Camera"),
                record(2, "sku-2", "Acme", "Router"),
                record(3, "sku-3", "Acme", "Cable"))))
                .isInstanceOf(ForcedRepositoryFailure.class);

        assertThat(logicalTableState()).isEqualTo(before);
    }

    private LogicalTableState logicalTableState() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return new LogicalTableState(count(connection, "Product"), count(connection, "SellerProduct"));
        }
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.getLong(1);
        }
    }

    private ImportRecord record(int position, String sellerProductId, String sellerName, String name) {
        Map<String, RawValue> fields = new LinkedHashMap<>();
        fields.put("Id", RawValue.string(sellerProductId));
        fields.put("SellerName", RawValue.string(sellerName));
        fields.put("Name", RawValue.string(name));
        return ImportRecord.object(position, fields);
    }

    private record LogicalTableState(long products, long sellerProducts) {
    }

    static final class ForcedRepositoryFailure extends RuntimeException {
    }
}
