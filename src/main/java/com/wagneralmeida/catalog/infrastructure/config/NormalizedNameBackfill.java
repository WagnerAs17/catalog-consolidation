package com.wagneralmeida.catalog.infrastructure.config;

import com.wagneralmeida.catalog.domain.TextNormalizer;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductEntity;
import com.wagneralmeida.catalog.infrastructure.out.persistence.ProductJpaRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NormalizedNameBackfill implements ApplicationRunner {

    private final ProductJpaRepository repository;
    private final TextNormalizer normalizer;

    public NormalizedNameBackfill(ProductJpaRepository repository, TextNormalizer normalizer) {
        this.repository = repository;
        this.normalizer = normalizer;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        for (ProductEntity product : repository.findAllByNormalizedNameIsNull()) {
            product.setNormalizedName(normalizer.normalize(product.getName()));
        }

        repository.flush();
        if (repository.countByNormalizedNameIsNull() != 0) {
            throw new IllegalStateException("NormalizedName backfill left NULL values");
        }
    }
}
