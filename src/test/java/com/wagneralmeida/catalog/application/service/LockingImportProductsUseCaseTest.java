package com.wagneralmeida.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportRecord.RawValue;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductRepositoryAdapter;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(classes = CatalogApplication.class)
class LockingImportProductsUseCaseTest {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private LockingImportProductsUseCase useCase;

    @Autowired
    private ImportLock lock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean(name = "productRepository")
    private ProductRepositoryAdapter products;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM SellerProduct");
        jdbcTemplate.update("DELETE FROM Product");
        jdbcTemplate.update("DELETE FROM sqlite_sequence WHERE name IN ('Product', 'SellerProduct')");
    }

    @Test
    void rejectsImmediatelyWhenAnotherImportOwnsTheLock() {
        assertThat(lock.tryAcquire()).isTrue();

        try {
            assertThatExceptionOfType(ImportLock.ImportInProgressException.class)
                    .isThrownBy(() -> useCase.importProducts(List.of()));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Product", Long.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SellerProduct", Long.class)).isZero();
        } finally {
            lock.release();
        }
    }

    @Test
    void delegatesWhileLockedAndReleasesAfterSuccessAndFailure() {
        assertThat(useCase.importProducts(List.of()).results()).isEmpty();
        assertThat(lock.tryAcquire()).isTrue();
        lock.release();

        doThrow(new ForcedFailure()).when(products).findByNormalizedName(anyString());
        assertThatThrownBy(() -> useCase.importProducts(List.of(record())))
                .isInstanceOf(ForcedFailure.class);
        assertThat(lock.tryAcquire()).isTrue();
        lock.release();
    }

    private ImportRecord record() {
        Map<String, RawValue> fields = new LinkedHashMap<>();
        fields.put("Id", RawValue.string("sku-1"));
        fields.put("SellerName", RawValue.string("Acme"));
        fields.put("Name", RawValue.string("Camera"));
        return ImportRecord.object(1, fields);
    }

    private static final class ForcedFailure extends RuntimeException {
    }
}
