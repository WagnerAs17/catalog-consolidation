package com.wagneralmeida.catalog.infrastructure.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "Product")
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "Name", nullable = false)
    private String name;

    @Column(name = "Brand")
    private String brand;

    @Column(name = "Category")
    private String category;

    @Column(name = "NormalizedName")
    private String normalizedName;

    protected ProductEntity() {
    }

    public ProductEntity(String name, String brand, String category, String normalizedName) {
        this.name = name;
        this.brand = brand;
        this.category = category;
        this.normalizedName = normalizedName;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getCategory() {
        return category;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }
}
