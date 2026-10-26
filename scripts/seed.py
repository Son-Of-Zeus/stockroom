from api_client import Api


def seed():
    api = Api()
    if len(api.key) < 24:
        raise SystemExit('Set OWNER_ACCESS_KEY to your configured owner key.')
    suppliers = api.ok('GET', '/api/v1/suppliers?search=Northline&size=100')['items']
    supplier = next((row for row in suppliers if row['name'] == 'Northline Supply'), None)
    if supplier is None:
        supplier = api.ok('POST', '/api/v1/suppliers', {'name': 'Northline Supply', 'email': 'orders@example.test', 'phone': ''})
    warehouses = []
    for code, name, location in [('WH-NORTH', 'North warehouse', 'Chennai · Ambattur'), ('WH-SOUTH', 'South warehouse', 'Chennai · Guindy')]:
        records = api.ok('GET', '/api/v1/warehouses?search=' + name.replace(' ', '%20'))['items']
        warehouse = next((row for row in records if row['code'] == code), None)
        if warehouse is None:
            warehouse = api.ok('POST', '/api/v1/warehouses', {'code': code, 'name': name, 'location': location})
        warehouses.append(warehouse)
    samples = [
        ('BRG-001', 'Precision bearing', 'Mechanical', 20, 180, 12),
        ('CBL-002', 'Power cable · 2m', 'Electrical', 30, 240, 90),
        ('MTR-003', 'Drive motor', 'Mechanical', 8, 6, 24),
        ('VLV-004', 'Control valve', 'Plumbing', 15, 64, 10),
        ('SNS-005', 'Temperature sensor', 'Electronics', 12, 90, 48),
        ('PMP-006', 'Water pump', 'Plumbing', 5, 18, 4),
        ('BLT-007', 'Steel bolt · M10', 'Hardware', 100, 1200, 750),
        ('RLY-008', 'Safety relay', 'Electrical', 10, 42, 18),
    ]
    for sku, name, category, level, north, south in samples:
        records = api.ok('GET', '/api/v1/products?search=' + sku)['items']
        product = next((row for row in records if row['sku'] == sku), None)
        if product is None:
            product = api.ok('POST', '/api/v1/products', {'sku': sku, 'name': name, 'category': category, 'supplierId': supplier['id'], 'reorderLevel': level})
        for warehouse, quantity in zip(warehouses, [north, south]):
            api.ok('POST', '/api/v1/stock/receipts', {'productId': product['id'], 'warehouseId': warehouse['id'], 'quantity': quantity, 'note': 'Opening demo stock'}, headers={'Idempotency-Key': f'seed:v1:{sku}:{warehouse["id"]}'})
    print('Sample data is ready. Repeating this command does not repeat the opening receipts.')


if __name__ == '__main__':
    seed()
