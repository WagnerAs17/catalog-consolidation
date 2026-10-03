package com.wagneralmeida.catalog.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.application.port.out.ProductRepository;
import com.wagneralmeida.catalog.application.port.out.SellerProductRepository;
import com.wagneralmeida.catalog.application.service.ImportLock;
import com.wagneralmeida.catalog.application.service.ImportProductsService;
import com.wagneralmeida.catalog.application.service.LockingImportProductsUseCase;
import com.wagneralmeida.catalog.domain.TextNormalizer;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductJpaRepository;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductRepositoryAdapter;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SellerProductJpaRepository;
import com.wagneralmeida.catalog.infrastructure.out.persistence.SellerProductRepositoryAdapter;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@Configuration(proxyBeanMethods = false)
public class SqliteConfig {

    @Bean
    DataSource dataSource(@Value("${catalog.db.path:./data/work/catalog.db}") String databasePath) {
        Path absolutePath = Path.of(databasePath).toAbsolutePath().normalize();

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + absolutePath
                + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    @Bean
    ProductRepositoryAdapter productRepository(ProductJpaRepository repository) {
        return new ProductRepositoryAdapter(repository);
    }

    @Bean
    SellerProductRepositoryAdapter sellerProductRepository(SellerProductJpaRepository repository) {
        return new SellerProductRepositoryAdapter(repository);
    }

    @Bean
    TextNormalizer textNormalizer() {
        return new TextNormalizer();
    }

    @Bean
    ImportLock importLock() {
        return new ImportLock();
    }

    @Bean
    ImportProductsService importProductsService(
            ProductRepository productRepository,
            SellerProductRepository sellerProductRepository,
            TextNormalizer textNormalizer) {
        return new ImportProductsService(productRepository, sellerProductRepository, textNormalizer);
    }

    @Bean("transactionalImportProductsUseCase")
    ImportProductsUseCase transactionalImportProductsUseCase(
            ImportProductsService importProductsService,
            PlatformTransactionManager transactionManager) {
        NameMatchTransactionAttributeSource transactionAttributes = new NameMatchTransactionAttributeSource();
        transactionAttributes.addTransactionalMethod("importProducts", new DefaultTransactionAttribute());

        ProxyFactory proxyFactory = new ProxyFactory(importProductsService);
        proxyFactory.setInterfaces(ImportProductsUseCase.class);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, transactionAttributes));
        return (ImportProductsUseCase) proxyFactory.getProxy();
    }

    @Bean
    @Primary
    LockingImportProductsUseCase lockingImportProductsUseCase(
            @Qualifier("transactionalImportProductsUseCase") ImportProductsUseCase transactionalUseCase,
            ImportLock importLock) {
        return new LockingImportProductsUseCase(transactionalUseCase, importLock);
    }
}
