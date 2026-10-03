ALTER TABLE Product ADD COLUMN NormalizedName TEXT;
CREATE UNIQUE INDEX UX_Product_NormalizedName ON Product (NormalizedName);
DROP TABLE SellerProduct;
CREATE TABLE SellerProduct (
    Id              INTEGER PRIMARY KEY AUTOINCREMENT,
    SellerName      TEXT    NOT NULL,
    SellerProductId TEXT    NOT NULL,
    ProductId       INTEGER NOT NULL REFERENCES Product (Id),
    CONSTRAINT UX_SellerProduct_Seller_Code UNIQUE (SellerName, SellerProductId)
);
CREATE INDEX IX_SellerProduct_ProductId ON SellerProduct (ProductId);
