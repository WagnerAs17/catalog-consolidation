package com.wagneralmeida.catalog.application.port.out;

import com.wagneralmeida.catalog.domain.SellerProduct;
import java.util.Optional;

public interface SellerProductRepository {

    Optional<SellerProduct> findBySellerNameAndSellerProductId(
            String sellerName, String sellerProductId);

    SellerProduct create(SellerProduct sellerProduct);
}
