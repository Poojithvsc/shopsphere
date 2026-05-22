package com.shopsphere.catalog;

import com.shopsphere.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
class ProductPriceLookupImpl implements ProductPriceLookup {

    private final ProductRepository products;

    ProductPriceLookupImpl(ProductRepository products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Money> findUnitPrice(UUID productId) {
        return products.findById(productId).map(Product::getUnitPrice);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSummary> findSummary(UUID productId) {
        return products.findById(productId)
                .map(p -> new ProductSummary(p.getId(), p.getName(), p.getUnitPrice()));
    }
}
