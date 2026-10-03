package com.wagneralmeida.catalog.infrastructure.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerProductJpaRepository extends JpaRepository<SellerProductEntity, Long> {

    Optional<SellerProductEntity> findBySellerNameAndSellerProductId(
            String sellerName, String sellerProductId);
}
