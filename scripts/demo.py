#!/usr/bin/env python3
"""Repeatable E2E demonstration against Docker Compose, using only Python's standard library."""
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
settings = {}
if (ROOT / '.env').exists():
    for line in (ROOT / '.env').read_text().splitlines():
        if line.strip() and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            settings[key] = value.strip().strip('\"\'')
settings.update(os.environ)
BASE = settings.get('BASE_URL', 'http://localhost:' + settings.get('APP_PORT', '8080'))
SUFFIX = uuid.uuid4().hex[:10]


def redact(data):
    if isinstance(data, dict):
        return {k: '***' if 'token' in k or 'password' in k else redact(v) for k, v in data.items()}
    if isinstance(data, list):
        return [redact(v) for v in data]
    return data


def call(method, path, body=None, token=None, expected=200, code=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                     headers=headers, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as exc:
        response = exc
    with response:
        data = response.read()
        result = json.loads(data) if data else None
        print(f'\n{method} {path} → {response.status}, X-Request-Id: {response.headers.get("X-Request-Id")}', flush=True)
        print(json.dumps(redact(result), ensure_ascii=False, indent=2), flush=True)
        assert response.status == expected, (expected, response.status, result)
        uuid.UUID(response.headers['X-Request-Id'])
        if code:
            assert result['error_code'] == code, result
        return result


def account(role):
    credentials = {'email': f'{role.lower()}-{uuid.uuid4().hex[:8]}@demo.local', 'password': 'Demo-password-2026!'}
    registered = call('POST', '/auth/register', {**credentials, 'role': role}, expected=201)
    pair = call('POST', '/auth/login', credentials)
    return registered['id'], pair['access_token'], pair['refresh_token']


def tables(product_id, order_id):
    # IDs are UUIDs returned by this API; validate before using in this demonstration SQL.
    uuid.UUID(product_id)
    uuid.UUID(order_id)
    query = f"""
SELECT id,name,price,stock,status,seller_id,created_at,updated_at FROM products WHERE id='{product_id}';
SELECT * FROM orders WHERE id='{order_id}';
SELECT * FROM order_items WHERE order_id='{order_id}';
SELECT code,current_uses,max_uses FROM promo_codes WHERE code='DEMO_{SUFFIX.upper()}';
SELECT operation_type,created_at FROM user_operations WHERE user_id=(SELECT user_id FROM orders WHERE id='{order_id}');
"""
    print('\nPostgreSQL: ' + query, flush=True)
    subprocess.run(['docker', 'compose', 'exec', '-T', 'db', 'psql', '-U', 'marketplace', '-d', 'marketplace',
                    '-v', 'ON_ERROR_STOP=1', '-c', query], cwd=ROOT, check=True)


def main():
    _, seller, _ = account('SELLER')
    _, buyer, refresh = account('USER')
    _, stranger, _ = account('USER')
    body = {'name': 'Demo keyboard', 'description': None, 'price': 100, 'stock': 10,
            'category': 'demo-' + SUFFIX, 'status': 'ACTIVE'}
    product = call('POST', '/products', body, seller, 201)
    pid = product['id']
    call('GET', f'/products?status=ACTIVE&category=demo-{SUFFIX}&page=0&size=5', token=buyer)
    call('POST', '/products', body, buyer, 403, 'ACCESS_DENIED')
    call('POST', '/products', {**body, 'price': -1}, seller, 400, 'VALIDATION_ERROR')
    call('GET', '/products', token='broken-token', expected=401, code='TOKEN_INVALID')
    promo = 'DEMO_' + SUFFIX.upper()
    now = datetime.now(timezone.utc)
    call('POST', '/promo-codes', {'code': promo, 'discount_type': 'PERCENTAGE', 'discount_value': 20,
         'min_order_amount': 200, 'max_uses': 1, 'active': True,
         'valid_from': (now - timedelta(minutes=1)).isoformat(),
         'valid_until': (now + timedelta(days=1)).isoformat()}, seller, 201)
    items = lambda n: {'items': [{'product_id': pid, 'quantity': n}]}
    call('POST', '/orders', items(11), buyer, 409, 'INSUFFICIENT_STOCK')
    call('POST', '/orders', {**items(2), 'promo_code': 'MISSING_' + SUFFIX.upper()}, buyer, 422, 'PROMO_CODE_INVALID')
    assert call('GET', '/products/' + pid, token=buyer)['stock'] == 10
    order = call('POST', '/orders', {**items(3), 'promo_code': promo}, buyer, 201)
    oid = order['id']
    assert order['total_amount'] == 240 and order['discount_amount'] == 60
    tables(pid, oid)
    call('POST', '/orders', items(1), buyer, 429, 'ORDER_LIMIT_EXCEEDED')
    call('GET', '/orders/' + oid, token=stranger, expected=403, code='ORDER_OWNERSHIP_VIOLATION')
    call('PUT', '/products/' + pid, {**body, 'price': 150, 'stock': 7}, seller)
    assert call('GET', '/orders/' + oid, token=buyer)['total_amount'] == 240
    changed = call('PUT', '/orders/' + oid, items(1), buyer)
    assert changed['total_amount'] == 100 and changed['discount_amount'] == 0 and changed['promo_code_id'] is None
    tables(pid, oid)
    call('POST', '/orders/' + oid + '/cancel', token=buyer)
    call('POST', '/orders/' + oid + '/cancel', token=buyer, expected=409, code='INVALID_STATE_TRANSITION')
    assert call('GET', '/products/' + pid, token=buyer)['stock'] == 10
    tables(pid, oid)
    pair = call('POST', '/auth/refresh', {'refresh_token': refresh})
    call('POST', '/auth/refresh', {'refresh_token': refresh}, expected=401, code='REFRESH_TOKEN_INVALID')
    call('GET', '/products/' + pid, token=pair['access_token'])
    admin = call('POST', '/auth/login', {'email': settings.get('ADMIN_EMAIL', 'admin@example.com'),
                                       'password': settings['ADMIN_PASSWORD']})['access_token']
    second = call('POST', '/orders', items(1), stranger, 201)['id']
    call('PATCH', '/orders/' + second + '/status', {'status': 'COMPLETED'}, admin, 409, 'INVALID_STATE_TRANSITION')
    for status in ['PAYMENT_PENDING', 'PAID', 'SHIPPED', 'COMPLETED']:
        call('PATCH', '/orders/' + second + '/status', {'status': status}, admin)
    call('POST', '/orders/' + second + '/cancel', token=stranger, expected=409, code='INVALID_STATE_TRANSITION')
    call('DELETE', '/products/' + pid, token=seller, expected=204)
    assert call('GET', '/products/' + pid, token=buyer)['status'] == 'ARCHIVED'
    tables(pid, second)
    print('\nE2E PASSED: HTTP → authorization → business logic → PostgreSQL, including error scenarios.', flush=True)


if __name__ == '__main__':
    main()
