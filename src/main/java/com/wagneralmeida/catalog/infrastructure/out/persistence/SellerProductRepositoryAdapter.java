package com.wagneralmeida.catalog.infrastructure.out.persistence;

import com.wagneralmeida.catalog.application.port.out.SellerProductRepository;
import com.wagneralmeida.catalog.domain.SellerProduct;
import java.util.Optional;

public class SellerProductRepositoryAdapter implements SellerProductRepository {

    private final SellerProductJpaRepository repository;

    public SellerProductRepositoryAdapter(SellerProductJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SellerProduct> findBySellerNameAndSellerProductId(
            String sellerName, String sellerProductId) {
        return repository.findBySellerNameAndSellerProductId(
                        sellerName.trim(), sellerProductId.trim())
                .map(SellerProductRepositoryAdapter::toDomain);
    }

    @Override
    public SellerProduct create(SellerProduct sellerProduct) {
        if (sellerProduct.id() != null) {
            throw new IllegalArgumentException("A new seller product must not already have an identifier");
        }

        SellerProductEntity entity = new SellerProductEntity(
                sellerProduct.sellerName().trim(),
                sellerProduct.sellerProductId().trim(),
                sellerProduct.productId());
        return toDomain(repository.saveAndFlush(entity));
    }

    private static SellerProduct toDomain(SellerProductEntity entity) {
        return new SellerProduct(
                entity.getId(),
                entity.getSellerName(),
                entity.getSellerProductId(),
                entity.getProductId());
    }
}
