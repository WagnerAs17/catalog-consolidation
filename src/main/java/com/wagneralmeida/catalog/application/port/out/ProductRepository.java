package com.wagneralmeida.catalog.application.port.out;

import com.wagneralmeida.catalog.domain.Product;
import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findByNormalizedName(String normalizedName);

    Product create(Product product);
}
