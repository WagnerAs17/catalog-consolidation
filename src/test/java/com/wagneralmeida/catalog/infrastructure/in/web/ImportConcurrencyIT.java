package com.wagneralmeida.catalog.infrastructure.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wagneralmeida.catalog.CatalogApplication;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductRepositoryAdapter;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SellerProductRepositoryAdapter;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SqliteIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
class ImportConcurrencyIT {

    private static final Path DATABASE = SqliteIntegrationTestSupport.createDatabase();

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        SqliteIntegrationTestSupport.registerCatalogDbPath(registry, DATABASE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean(name = "productRepository")
    private ProductRepositoryAdapter products;

    @MockitoSpyBean(name = "sellerProductRepository")
    private SellerProductRepositoryAdapter listings;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM SellerProduct");
        jdbcTemplate.update("DELETE FROM Product");
        jdbcTemplate.update("DELETE FROM sqlite_sequence WHERE name IN ('Product', 'SellerProduct')");
    }

    @Test
    void parallelImportGets409AndLockIsReleasedAfterSuccess() throws Exception {
        LookupGate gate = holdNextLookup(false);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> first = executor.submit(this::performImport);
            assertThat(gate.entered().await(5, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(multipart("/imports").file(jsonFile()))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("IMPORT_IN_PROGRESS"));
            verify(products, times(1)).findByNormalizedName("camera");
            verify(products, never()).create(org.mockito.ArgumentMatchers.any());
            verify(listings, never()).findBySellerNameAndSellerProductId(anyString(), anyString());
            verify(listings, never()).create(org.mockito.ArgumentMatchers.any());

            gate.release().countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(count("Product")).isOne();
            assertThat(count("SellerProduct")).isOne();

            mockMvc.perform(multipart("/imports").file(jsonFile()))
                    .andExpect(status().isOk());
        } finally {
            gate.release().countDown();
        }
    }

    @Test
    void lockIsReleasedAfterFailedImport() throws Exception {
        LookupGate gate = holdNextLookup(true);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> first = executor.submit(this::performImport);
            assertThat(gate.entered().await(5, TimeUnit.SECONDS)).isTrue();
            gate.release().countDown();

            MvcResult failed = first.get(5, TimeUnit.SECONDS);
            assertThat(failed.getResponse().getStatus()).isEqualTo(500);
            assertThat(failed.getResponse().getContentAsString()).contains("\"code\":\"INTERNAL_ERROR\"");
            assertThat(count("Product")).isZero();
            assertThat(count("SellerProduct")).isZero();

            mockMvc.perform(multipart("/imports").file(jsonFile()))
                    .andExpect(status().isOk());
        } finally {
            gate.release().countDown();
        }
    }

    private LookupGate holdNextLookup(boolean failAfterRelease) {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release held import");
                }
                if (failAfterRelease) {
                    throw new ForcedImportFailure();
                }
            }
            return invocation.callRealMethod();
        }).when(products).findByNormalizedName(anyString());
        return new LookupGate(entered, release);
    }

    private MvcResult performImport() throws Exception {
        return mockMvc.perform(multipart("/imports").file(jsonFile())).andReturn();
    }

    private MockMultipartFile jsonFile() {
        String json = "[{\"Id\":\"sku-1\",\"SellerName\":\"Acme\",\"Name\":\"Camera\"}]";
        return new MockMultipartFile(
                "file", "products.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private record LookupGate(CountDownLatch entered, CountDownLatch release) {
    }

    private static final class ForcedImportFailure extends RuntimeException {
    }
}
