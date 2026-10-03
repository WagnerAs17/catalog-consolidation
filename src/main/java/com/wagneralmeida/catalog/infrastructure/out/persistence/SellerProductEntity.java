package com.wagneralmeida.catalog.infrastructure.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "SellerProduct")
public class SellerProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "SellerName", nullable = false)
    private String sellerName;

    @Column(name = "SellerProductId", nullable = false)
    private String sellerProductId;

    @Column(name = "ProductId", nullable = false, columnDefinition = "INTEGER")
    private Long productId;

    protected SellerProductEntity() {
    }

    public SellerProductEntity(String sellerName, String sellerProductId, Long productId) {
        this.sellerName = sellerName;
        this.sellerProductId = sellerProductId;
        this.productId = productId;
    }

    public Long getId() {
        return id;
    }

    public String getSellerName() {
        return sellerName;
    }

    public String getSellerProductId() {
        return sellerProductId;
    }

    public Long getProductId() {
        return productId;
    }
}
