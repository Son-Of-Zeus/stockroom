import concurrent.futures
import os
import time
import unittest
import uuid
from api_client import Api


class InventoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api = Api()
        cls.second = Api(os.environ.get('SECOND_APP_URL', cls.api.base))
        cls.second.key = cls.api.key
        cls.suffix = uuid.uuid4().hex[:8]
        cls.supplier = cls.api.ok('POST', '/api/v1/suppliers', {'name': 'Test supplier ' + cls.suffix, 'email': '', 'phone': ''})
        cls.warehouses = [cls.api.ok('POST', '/api/v1/warehouses', {'name': 'Test warehouse ' + str(i) + cls.suffix, 'code': f'IT-{cls.suffix}-{i}', 'location': 'Test'}) for i in range(2)]
        cls.product = cls.api.ok('POST', '/api/v1/products', {'name': 'Test bearing ' + cls.suffix, 'sku': 'IT-' + cls.suffix, 'category': 'Parts', 'supplierId': cls.supplier['id'], 'reorderLevel': 3})
        cls.api.ok('POST', '/api/v1/stock/receipts', cls.payload(8), headers={'Idempotency-Key': 'opening-' + cls.suffix})

    @classmethod
    def payload(cls, quantity, destination=None):
        data = {'productId': cls.product['id'], 'warehouseId': cls.warehouses[0]['id'], 'quantity': quantity}
        if destination:
            data['destinationWarehouseId'] = destination
        return data

    def test_01_owner_access_is_required(self):
        self.assertEqual(401, self.api.request('GET', '/api/v1/dashboard', key='')[0])
        self.assertEqual(401, self.api.request('GET', '/api/v1/dashboard', key='wrong')[0])

    def test_02_duplicate_sku_and_validation(self):
        data = {'name': 'Duplicate', 'sku': self.product['sku'], 'category': 'Parts', 'supplierId': self.supplier['id'], 'reorderLevel': 3}
        self.assertEqual(409, self.api.request('POST', '/api/v1/products', data)[0])
        self.assertEqual(400, self.api.request('POST', '/api/v1/stock/issues', self.payload(-1), headers={'Idempotency-Key': 'negative'})[0])
        self.assertEqual(400, self.api.request('POST', '/api/v1/stock/issues', self.payload(1.5), headers={'Idempotency-Key': 'fraction'})[0])
        self.assertEqual(400, self.api.request('POST', '/api/v1/stock/issues', self.payload(1))[0])
        self.assertEqual(400, self.api.request('GET', '/api/v1/products?size=101')[0])
        status, body, _ = self.api.request('GET', '/api/v1/autocomplete')
        self.assertEqual(400, status)
        self.assertEqual(400, body['status'])
        self.assertEqual(400, self.api.request('POST', '/api/v1/suppliers', {'name': 'Invalid', 'unexpected': True})[0])

    def test_03_manager_scope_and_revocation(self):
        result = self.api.ok('POST', f'/api/v1/warehouses/{self.warehouses[0]["id"]}/links', {'label': 'Test manager'})
        code = result['url'].rsplit('/', 1)[1]
        status, view, headers = self.api.request('GET', '/api/shared/warehouse', key=code)
        self.assertEqual(200, status)
        self.assertEqual(self.warehouses[0]['id'], view['warehouse']['id'])
        self.assertTrue(all(row['warehouseId'] == self.warehouses[0]['id'] for row in view['stock']['items']))
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual('no-referrer', headers['Referrer-Policy'])
        self.assertNotIn('codeHash', str(view))
        self.assertNotIn('destinationWarehouseId', str(view['movements']))
        self.assertEqual(401, self.api.request('GET', '/api/v1/warehouses', key=code)[0])
        self.assertEqual(405, self.api.request('POST', '/api/shared/stock', self.payload(1), key=code)[0])
        self.assertEqual(400, self.api.request('GET', '/api/shared/stock?warehouseId=' + self.warehouses[1]['id'], key=code)[0])
        listed = self.api.ok('GET', f'/api/v1/warehouses/{self.warehouses[0]["id"]}/links')
        self.assertNotIn(code, str(listed))
        self.assertNotIn('codeHash', str(listed))
        self.api.ok('DELETE', f'/api/v1/warehouses/{self.warehouses[0]["id"]}/links/{result["link"]["id"]}')
        self.assertEqual(404, self.second.request('GET', '/api/shared/warehouse', key=code)[0])
        self.assertEqual(404, self.api.request('GET', '/api/shared/warehouse', key='A' * 22)[0])

    def test_04_idempotency_across_instances(self):
        key = 'repeat-' + self.suffix
        first = self.api.ok('POST', '/api/v1/stock/receipts', self.payload(2), headers={'Idempotency-Key': key})
        second = self.second.ok('POST', '/api/v1/stock/receipts', self.payload(2), headers={'Idempotency-Key': key})
        self.assertEqual(first, second)
        self.assertEqual(409, self.api.request('POST', '/api/v1/stock/receipts', self.payload(3), headers={'Idempotency-Key': key})[0])

    def test_05_concurrent_issues_across_instances(self):
        def issue(i):
            api = self.api if i % 2 == 0 else self.second
            return api.request('POST', '/api/v1/stock/issues', self.payload(1), headers={'Idempotency-Key': f'issue-{self.suffix}-{i}'})[0]
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            statuses = list(executor.map(issue, range(16)))
        self.assertEqual(10, statuses.count(200), statuses)
        self.assertEqual(6, statuses.count(409), statuses)
        rows = self.api.ok('GET', f'/api/v1/stock?warehouseId={self.warehouses[0]["id"]}&productId={self.product["id"]}')['items']
        self.assertEqual(0, rows[0]['quantity'])
        low = self.api.ok('GET', f'/api/v1/stock?low=true&warehouseId={self.warehouses[0]["id"]}')
        self.assertEqual(1, low['total'])

    def test_06_transfer_and_permanent_history(self):
        self.api.ok('POST', '/api/v1/stock/receipts', self.payload(5), headers={'Idempotency-Key': 'transfer-stock-' + self.suffix})
        self.api.ok('POST', '/api/v1/stock/transfers', self.payload(2, self.warehouses[1]['id']), headers={'Idempotency-Key': 'transfer-' + self.suffix})
        rows = self.api.ok('GET', '/api/v1/stock?productId=' + self.product['id'])['items']
        self.assertEqual([2, 3], sorted(row['quantity'] for row in rows))
        self.assertEqual(409, self.api.request('POST', f'/api/v1/products/{self.product["id"]}/archive')[0])
        self.assertEqual(400, self.api.request('POST', '/api/v1/stock/transfers', self.payload(1, self.warehouses[0]['id']), headers={'Idempotency-Key': 'same-location'})[0])

    def test_07_autocomplete_and_shared_cache(self):
        prefix = 'Test bearing ' + self.suffix
        path = '/api/v1/autocomplete?prefix=' + prefix.replace(' ', '%20')
        first = self.api.ok('GET', path)
        second = self.second.ok('GET', path)
        self.assertEqual(first, second)
        self.assertTrue(any(row['id'] == self.product['id'] for row in first))
        system = self.second.ok('GET', '/api/v1/system')
        self.assertGreater(system['cache']['hits'], 0)
        self.assertEqual('token bucket', system['rateLimiter']['algorithm'])

    def test_08_global_token_bucket(self):
        statuses = []
        for i in range(150):
            api = self.api if i % 2 == 0 else self.second
            status, _, headers = api.request('GET', '/api/v1/products?size=1', retry=False)
            statuses.append(status)
            if status == 429:
                self.assertGreaterEqual(int(headers['Retry-After']), 1)
                break
        self.assertIn(429, statuses)
        time.sleep(1.1)
        self.assertEqual(200, self.second.request('GET', '/api/v1/products?size=1', retry=False)[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
