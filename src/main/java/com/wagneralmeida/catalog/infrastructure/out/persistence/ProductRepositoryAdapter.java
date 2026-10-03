package com.wagneralmeida.catalog.infrastructure.out.persistence;

import com.wagneralmeida.catalog.application.port.out.ProductRepository;
import com.wagneralmeida.catalog.domain.Product;
import java.util.Optional;

public class ProductRepositoryAdapter implements ProductRepository {

    private final ProductJpaRepository repository;

    public ProductRepositoryAdapter(ProductJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Product> findByNormalizedName(String normalizedName) {
        return repository.findByNormalizedName(normalizedName).map(ProductRepositoryAdapter::toDomain);
    }

    @Override
    public Product create(Product product) {
        ProductEntity entity = new ProductEntity(
                product.name(), product.brand(), product.category(), product.normalizedName());
        return toDomain(repository.saveAndFlush(entity));
    }

    private static Product toDomain(ProductEntity entity) {
        return new Product(
                entity.getId(),
                entity.getName(),
                entity.getBrand(),
                entity.getCategory(),
                entity.getNormalizedName());
    }
}
