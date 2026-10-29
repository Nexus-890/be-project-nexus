// Source: docs/api/openapi/catalog-service.yaml, SKU and product detail reads.
// Only the dependency endpoints used by the real commerce CatalogClient are modeled.
const http = require('http');
const prefix = '/api/v1/skus/';
const knownSku = 'c8000000-0000-4000-8000-000000000003';
const knownProduct = 'c8000000-0000-4000-8000-000000000002';
const unavailableSku = 'c8000000-0000-4000-8000-000000000005';
const malformedSku = 'c8000000-0000-4000-8000-000000000006';

http.createServer((req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  function json(status, body, type = 'application/json') {
    res.writeHead(status, { 'Content-Type': type });
    res.end(JSON.stringify(body));
  }
  function problem(status, title, code, detail) {
    json(status, {
      type: status === 404 ? 'urn:problem:domain-error' : 'urn:problem:service-unavailable',
      title, status, detail, instance: path, code
    }, 'application/problem+json');
  }
  if (req.method === 'GET' && path === `/api/v1/products/${knownProduct}`) {
    return json(200, {
      code: 0, message: 'response success',
      data: {
        id: knownProduct, sellerId: '68000000-0000-4000-8000-000000000002',
        name: 'Shoe', slug: 'shoe', status: 'ACTIVE',
        categoryId: '68000000-0000-4000-8000-000000000003', minPrice: 150000.5,
        description: 'Shoe', auctionEnable: false, images: [], skus: []
      }
    });
  }
  if (req.method !== 'GET' || !/^\/api\/v1\/skus\/[0-9a-f-]{36}$/.test(path)) {
    return json(501, { code: 'MOCK_ENDPOINT_NOT_MODELED' });
  }
  const id = path.slice(prefix.length);
  if (id === unavailableSku) {
    return problem(503, 'Service Unavailable', 'SERVICE_UNAVAILABLE', 'The upstream service is unavailable');
  }
  if (id === malformedSku) {
    // Deliberate contract violation: prove the caller rejects a legacy bare DTO.
    return json(200, { id, productId: 'c8000000-0000-4000-8000-000000000002', price: 150000.5 });
  }
  if (id !== knownSku) {
    return problem(404, 'Not Found', 'CATALOG_SKU_NOT_FOUND', 'SKU not found');
  }
  return json(200, {
    code: 0,
    message: 'response success',
    data: {
      id: knownSku,
      productId: 'c8000000-0000-4000-8000-000000000002',
      skuCode: 'SHOE-BLACK-42', color: 'Black', size: '42',
      price: 150000.5, status: 'ACTIVE'
    }
  });
}).listen(process.env.PORT || 4958);
