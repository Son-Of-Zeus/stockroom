import json
import os
import time
import urllib.error
import urllib.request


class Api:
    def __init__(self, base=None, key=None):
        self.base = (base or os.environ.get('STOCKROOM_URL', 'http://localhost:8080')).rstrip('/')
        self.key = key if key is not None else os.environ.get('OWNER_ACCESS_KEY', '')

    def request(self, method, path, data=None, key=None, headers=None, retry=True):
        auth = self.key if key is None else key
        values = {'Content-Type': 'application/json'}
        if auth:
            values['Authorization'] = 'Bearer ' + auth
        values.update(headers or {})
        payload = json.dumps(data).encode() if data is not None else None
        for attempt in range(90):
            request = urllib.request.Request(self.base + path, data=payload, headers=values, method=method)
            try:
                response = urllib.request.urlopen(request, timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.code
                body = response.read()
                response_headers = dict(response.headers)
            if status == 429 and retry and attempt < 89:
                time.sleep(max(1, int(response_headers.get('Retry-After', '1'))))
                continue
            try:
                result = json.loads(body)
            except (json.JSONDecodeError, UnicodeDecodeError):
                result = None
            return status, result, response_headers
        raise RuntimeError('The request retry limit was reached.')

    def ok(self, method, path, data=None, **kwargs):
        status, body, _ = self.request(method, path, data, **kwargs)
        if status != 200:
            raise RuntimeError(f'{method} {path.split("?")[0]} returned {status}: {body}')
        return body
