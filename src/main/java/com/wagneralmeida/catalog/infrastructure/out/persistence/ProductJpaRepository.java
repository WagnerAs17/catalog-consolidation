package com.wagneralmeida.catalog.infrastructure.out.persistence;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<ProductEntity, Long> {

    Optional<ProductEntity> findByNormalizedName(String normalizedName);

    List<ProductEntity> findAllByNormalizedNameIsNull();

    long countByNormalizedNameIsNull();
}
