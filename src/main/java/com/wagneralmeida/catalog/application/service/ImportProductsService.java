package com.wagneralmeida.catalog.application.service;

import com.wagneralmeida.catalog.application.port.in.ImportProductsUseCase;
import com.wagneralmeida.catalog.application.port.out.ProductRepository;
import com.wagneralmeida.catalog.application.port.out.SellerProductRepository;
import com.wagneralmeida.catalog.domain.Identity;
import com.wagneralmeida.catalog.domain.ImportRecord;
import com.wagneralmeida.catalog.domain.ImportReport;
import com.wagneralmeida.catalog.domain.Outcome;
import com.wagneralmeida.catalog.domain.Product;
import com.wagneralmeida.catalog.domain.RejectionReason;
import com.wagneralmeida.catalog.domain.SellerProduct;
import com.wagneralmeida.catalog.domain.TextNormalizer;
import com.wagneralmeida.catalog.domain.WarningCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ImportProductsService implements ImportProductsUseCase {

    private static final String LISTING_LINKED_TO_ANOTHER_PRODUCT = "LISTING_LINKED_TO_ANOTHER_PRODUCT";

    private final ProductRepository productRepository;
    private final SellerProductRepository sellerProductRepository;
    private final TextNormalizer textNormalizer;

    public ImportProductsService(
            ProductRepository productRepository,
            SellerProductRepository sellerProductRepository,
            TextNormalizer textNormalizer) {
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.sellerProductRepository = Objects.requireNonNull(sellerProductRepository, "sellerProductRepository");
        this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer");
    }

    @Override
    public ImportReport importProducts(List<ImportRecord> records) {
        Objects.requireNonNull(records, "records");

        List<ImportReport.Result> results = new ArrayList<>(records.size());
        for (ImportRecord record : records) {
            results.add(process(record));
        }
        return new ImportReport(UUID.randomUUID(), results);
    }

    private ImportReport.Result process(ImportRecord record) {
        ImportRecord.Validation validation = record.validate(textNormalizer);
        if (validation instanceof ImportRecord.Invalid invalid) {
            return invalid.result();
        }

        ImportRecord.Valid valid = (ImportRecord.Valid) validation;
        Product candidate = productRepository.findByNormalizedName(valid.normalizedName()).orElse(null);
        Identity identity = Identity.decide(candidate, valid.normalizedBrand(), textNormalizer);

        if (identity instanceof Identity.Inconclusive inconclusive) {
            return ImportReport.Result.notProcessed(
                    valid.position(),
                    valid.sellerName(),
                    valid.sellerProductId(),
                    RejectionReason.INCONCLUSIVE,
                    inconclusive.reason());
        }

        SellerProduct listing = sellerProductRepository
                .findBySellerNameAndSellerProductId(valid.sellerName(), valid.sellerProductId())
                .orElse(null);

        if (listing != null) {
            return resultForExistingListing(valid, identity, listing);
        }
        if (identity instanceof Identity.Existing existing) {
            Product product = existing.product();
            sellerProductRepository.create(new SellerProduct(
                    null, valid.sellerName(), valid.sellerProductId(), product.id()));
            return ImportReport.Result.processed(
                    valid.position(),
                    valid.sellerName(),
                    valid.sellerProductId(),
                    Outcome.LINKED_TO_EXISTING,
                    product.id(),
                    product.warningsFor(valid, textNormalizer));
        }

        Product created = productRepository.create(Product.from(valid));
        sellerProductRepository.create(new SellerProduct(
                null, valid.sellerName(), valid.sellerProductId(), created.id()));
        return ImportReport.Result.processed(
                valid.position(),
                valid.sellerName(),
                valid.sellerProductId(),
                Outcome.CREATED_AND_LINKED,
                created.id(),
                List.of());
    }

    private ImportReport.Result resultForExistingListing(
            ImportRecord.Valid record, Identity identity, SellerProduct listing) {
        if (identity instanceof Identity.Existing existing
                && Objects.equals(listing.productId(), existing.product().id())) {
            Product product = existing.product();
            List<WarningCode> warnings = product.warningsFor(record, textNormalizer);
            return ImportReport.Result.processed(
                    record.position(),
                    record.sellerName(),
                    record.sellerProductId(),
                    Outcome.ALREADY_LINKED,
                    product.id(),
                    warnings);
        }

        return ImportReport.Result.notProcessed(
                record.position(),
                record.sellerName(),
                record.sellerProductId(),
                RejectionReason.INCONSISTENT,
                LISTING_LINKED_TO_ANOTHER_PRODUCT);
    }
}
