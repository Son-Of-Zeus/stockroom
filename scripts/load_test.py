import argparse
import concurrent.futures
import json
import math
import os
import time
from collections import Counter
from api_client import Api


def main():
    parser = argparse.ArgumentParser(description='Run a bounded GET load test. No software is installed.')
    parser.add_argument('--requests', type=int, default=120)
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--second-url', default=os.environ.get('SECOND_APP_URL'))
    args = parser.parse_args()
    if not 1 <= args.requests <= 10000 or not 1 <= args.workers <= 64:
        parser.error('Use 1..10000 requests and 1..64 workers.')
    api = Api()
    if len(api.key) < 24:
        parser.error('Set OWNER_ACCESS_KEY.')

    clients = [api, Api(args.second_url, api.key)] if args.second_url else [api]

    def request(index):
        started = time.perf_counter()
        try:
            status, _, headers = clients[index % len(clients)].request('GET', '/api/v1/dashboard', retry=False)
            return status, (time.perf_counter() - started) * 1000, headers.get('X-Instance-Id', '')
        except OSError:
            return 0, (time.perf_counter() - started) * 1000, ''

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        results = list(executor.map(request, range(args.requests)))
    elapsed = time.perf_counter() - started
    latency = sorted(row[1] for row in results)
    successful = [row for row in results if row[0] == 200]
    output = {'requests': len(results), 'workers': args.workers, 'elapsedSeconds': round(elapsed, 3),
              'completedRequestsPerSecond': round(len(results) / elapsed, 2),
              'successfulRequestsPerSecond': round(len(successful) / elapsed, 2),
              'statusCounts': dict(Counter(str(row[0]) for row in results)),
              'instanceCounts': dict(Counter(row[2] for row in results)),
              'latencyP50Ms': round(latency[math.ceil(len(latency) * .5) - 1], 2),
              'latencyP95Ms': round(latency[math.ceil(len(latency) * .95) - 1], 2),
              'note': 'This measures this local run, including rejected requests. It does not establish production capacity.'}
    print(json.dumps(output, indent=2))


if __name__ == '__main__':
    main()
