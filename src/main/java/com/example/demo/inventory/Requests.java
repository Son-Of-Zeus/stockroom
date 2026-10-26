package com.example.demo.inventory;

public final class Requests {
    private Requests() {}

    public record Product(String sku, String name, String category, String supplierId, Long reorderLevel) {}
    public record Warehouse(String code, String name, String location) {}
    public record Supplier(String name, String email, String phone) {}
    public record Stock(String productId, String warehouseId, String destinationWarehouseId, Long quantity, String note) {}
    public record Link(String label) {}
}
