# Setup and JMeter test guide

Run these steps yourself. The agent has not installed JMeter, downloaded images, or run JMeter. The supplied JMX was checked as XML only. It uses standard JMeter components and requires no plugins.

## 1. Prepare the tools

Use Docker Desktop, JDK 17, Python 3, and the Apache JMeter **binary** distribution from [Apache](https://jmeter.apache.org/download_jmeter.cgi). Download and extract JMeter yourself. You do not need host Maven, MongoDB, or Redis for the Docker setup. The app build runs in its Maven container.

For example, move the extracted JMeter directory to `~/Tools/jmeter`. On macOS, select the installed Java 17 runtime and set the JMeter location:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export JMETER_HOME="$HOME/Tools/jmeter"
java -version
"$JMETER_HOME/bin/jmeter" --version
docker version
docker compose version
python3 --version
```

If you use a different JMeter directory, change `JMETER_HOME`. Start Docker Desktop and wait for its engine to become ready. Do not run a JMeter installer or dependency manager through the agent.

## 2. Start Stockroom

```sh
cd /Users/vpranav/Desktop/Dev/SD-Proj/demo
cp -n .env.example .env
```

Edit `.env`. Set `OWNER_ACCESS_KEY` to your own random secret of at least 24 characters. A 64-character hexadecimal value is suitable. Retain the following settings for the first tests:

```properties
PUBLIC_BASE_URL=http://localhost:8080
HOST_BIND=127.0.0.1
RATE_CAPACITY=60
RATE_REFILL=2
```

Obtain images yourself and build:

```sh
docker pull mongo:8.0
docker pull redis:8-alpine
docker pull nginx:1.28-alpine
docker pull maven:3.9-eclipse-temurin-17
docker pull eclipse-temurin:17-jre
docker compose build app-a
docker compose up -d --no-build --pull never
docker compose ps
curl -i http://localhost:8080/health
```

The build can download Maven dependencies. Only you run this command. `app-b` uses the same `stockroom:local` image built for `app-a`.

Wait until both apps are healthy. The `mongo-init` service should exit with code 0; this is normal. `/health` should return HTTP 200. If startup fails, inspect these logs:

```sh
docker compose logs --tail=100 mongo-init app-a app-b
```

Open [Stockroom](http://localhost:8080) and enter the owner key. In the terminal, enter the same key when running `read`:

```sh
read -rs OWNER_ACCESS_KEY
export OWNER_ACCESS_KEY
python3 scripts/seed.py
```

The seed creates two warehouses and eight sample products. It is safe to repeat without duplicating opening receipts. For a separate correctness check before JMeter, run `python3 scripts/integration_test.py` against a disposable demo database. This adds test fixtures and permanent movement records.

## 3. Configure JMeter

Keep your owner key outside the JMX file:

```sh
mkdir -p .runtime/results .runtime/reports
cp -n tests/jmeter/local.properties.example .runtime/jmeter.properties
chmod 600 .runtime/jmeter.properties
```

Edit `.runtime/jmeter.properties`:

```properties
ownerKey=PASTE_THE_SAME_OWNER_KEY_HERE
host=127.0.0.1
port=8080
protocol=http
```

Replace the placeholder. Do not add quotes around a hexadecimal key. `.runtime` is ignored by Git.

Open the supplied plan:

```sh
"$JMETER_HOME/bin/jmeter" -q .runtime/jmeter.properties -t tests/jmeter/inventory-read.jmx
```

The plan has HTTP defaults, an owner authorization header, one Thread Group, a per-thread timer, a GET sampler, and a response-code assertion. Its defaults are one thread, five loops, a one-second delay per request, and expected HTTP 200.

For a small GUI check, add **Listener → View Results Tree** under the Thread Group, then press Start. Check response bodies and the `X-Instance-Id` response header. Requests through Nginx should show both `app-a` and `app-b` over repeated requests. Do not expect an exact 50/50 split from a small sample.

Do not save the owner key into the plan. Use the GUI to build and debug tests. Run load tests in CLI mode, as [Apache recommends](https://jmeter.apache.org/usermanual/get-started.html#non_gui). Disable or remove View Results Tree before a load run.

## 4. Smoke test

Run from the `demo` directory:

```sh
run_id="smoke-$(date +%Y%m%d-%H%M%S)"
"$JMETER_HOME/bin/jmeter" -n \
  -q .runtime/jmeter.properties \
  -t tests/jmeter/inventory-read.jmx \
  -Jthreads=1 -Jloops=5 -Jramp=1 -Jdelay=1000 \
  -l ".runtime/results/$run_id.jtl" \
  -e -o ".runtime/reports/$run_id"
open ".runtime/reports/$run_id/index.html"
```

Expected: five HTTP 200 responses, zero assertion errors, and valid dashboard JSON. On a rate-limited system, wait for bucket refill before repeating.

JMeter requires the HTML report output directory to be new or empty. The timestamp keeps each run separate. The `open` command is for macOS; elsewhere, open the HTML file in your browser.

## 5. Token bucket test

Keep `RATE_CAPACITY=60` and `RATE_REFILL=2`. Close the Stockroom browser tabs and wait 35 seconds for a full bucket. Do not run seed or other API tests at the same time.

```sh
run_id="bucket-$(date +%Y%m%d-%H%M%S)"
"$JMETER_HOME/bin/jmeter" -n \
  -q .runtime/jmeter.properties \
  -t tests/jmeter/inventory-read.jmx \
  -Jthreads=120 -Jloops=1 -Jramp=0 -Jdelay=0 \
  -JexpectedCodes='200|429' \
  -l ".runtime/results/$run_id.jtl" \
  -e -o ".runtime/reports/$run_id"
open ".runtime/reports/$run_id/index.html"
```

Expected: an initial accepted burst and HTTP 429 responses after tokens run out, if requests arrive faster than refill. Do not assert exactly 60 successes: refill continues during the run. Each virtual user shares the same client IP, so all 120 threads use the same bucket across both apps.

The plan uses a Response Assertion with **Response Code**, **Matches**, and **Ignore Status**. For this run, both 200 and 429 are expected. A zero assertion error rate therefore does not mean every request was accepted. Count the actual codes:

```sh
python3 - ".runtime/results/$run_id.jtl" <<'PY'
import csv
import sys
from collections import Counter
with open(sys.argv[1], newline='') as source:
    rows = list(csv.DictReader(source))
print('HTTP codes:', dict(Counter(row['responseCode'] for row in rows)))
print('Assertion failures:', sum(row['success'].lower() != 'true' for row in rows))
PY
```

For a refill check, wait one second and send a single request from the GUI. It should succeed after the bucket has refilled. A rejected response must include `Retry-After`; check it under Response Headers in View Results Tree.

## 6. API load test

Raise the application limits for this separate experiment. This prevents the normal two-requests-per-second allowance from determining the result. Run this yourself:

```sh
RATE_CAPACITY=10000 RATE_REFILL=10000 docker compose up -d \
  --no-deps --force-recreate --no-build --pull never app-a app-b
docker compose ps
```

Wait until both apps are healthy. Then restart Nginx so it resolves the recreated app containers again:

```sh
docker compose restart nginx
curl -i http://localhost:8080/health
```

Run a small test first:

```sh
run_id="load10-$(date +%Y%m%d-%H%M%S)"
"$JMETER_HOME/bin/jmeter" -n \
  -q .runtime/jmeter.properties \
  -t tests/jmeter/inventory-read.jmx \
  -Jthreads=10 -Jloops=20 -Jramp=5 -Jdelay=1000 \
  -JexpectedCodes=200 \
  -l ".runtime/results/$run_id.jtl" \
  -e -o ".runtime/reports/$run_id"
open ".runtime/reports/$run_id/index.html"
```

Repeat with 50 and then 100 threads. Give each run a new `run_id`. Observe Docker resource use in another terminal with `docker stats`. Stop increasing load if the computer is saturated.

Record thread count, sample count, throughput, P50, P95, P99, assertion error percentage, and CPU/memory use. Compare runs on the same computer and with the same data. These are local measurements, not proof of million-user capacity.

Change the GET target with `-Jpath=/api/v1/products` or `-Jpath='/api/v1/autocomplete?prefix=be'`. Repeated autocomplete calls with the same prefix should use the same hash-selected node and increase cache hits. The 15-second expiry still applies. The owner-only `/api/v1/system` endpoint shows cache routing and counters for the responding app instance.

## 7. Stock correctness test in JMeter

Keep the raised limits for this test. Use a new test product and a new test warehouse. Receive exactly ten units into that warehouse. Do not use a sample balance with existing stock.

Get their string IDs from `GET /api/v1/products` and `GET /api/v1/warehouses`, using the owner header, or inspect these JSON responses in JMeter. Add to the private properties file:

```properties
productId=YOUR_TEST_PRODUCT_ID
warehouseId=YOUR_TEST_WAREHOUSE_ID
```

Save a copy of the plan as `tests/jmeter/inventory-issue.jmx` in JMeter. Change its HTTP sampler:

- Method: `POST`.
- Path: `/api/v1/stock/issues`.
- Body Data:

```json
{"productId":"${__P(productId,)}","warehouseId":"${__P(warehouseId,)}","quantity":1,"note":"JMeter stock test"}
```

Add an HTTP Header Manager **under this sampler**:

| Name | Value |
| --- | --- |
| `Content-Type` | `application/json` |
| `Idempotency-Key` | `${__UUID()}` |

The existing owner header remains in effect. Use 20 threads, one loop, zero ramp-up, zero delay, and `-JexpectedCodes='200|409'`. Use the same CLI command pattern, with the copied JMX and a new results/report name.

Expected: ten HTTP 200 responses, ten HTTP 409 responses for insufficient stock, and a final balance of zero. Check the balance through `/api/v1/stock?productId=YOUR_ID&warehouseId=YOUR_ID` and check movement history. The UUID gives each issue a different request key. Do not use one constant idempotency key for the whole test; that would replay one operation instead of testing concurrent issues.

To test idempotency separately, start with a fresh product/balance. Send repeated matching receipt requests with one fixed key. The balance must increase once, and every successful replay must return the same movement ID.

## 8. Failure and manager-link checks

These checks use your disposable local demo. Keep read requests running at a small rate while testing a failure.

| Check | Action | Expected result |
| --- | --- | --- |
| App failover | `docker compose stop app-a` | Requests continue through `app-b`; check `X-Instance-Id`. |
| Cache fallback | `docker compose stop cache-a cache-b` | Autocomplete still returns MongoDB results. |
| Required Redis failure | `docker compose stop rate-redis` | API requests return 503. |
| Manager scope | Generate a manager link; send its code as a Bearer token to `/api/shared/warehouse`. | Only the linked warehouse is returned. |
| Scope override | Add `warehouseId=another-id` to a shared API request. | HTTP 400. |
| Owner API protection | Send a manager code to `/api/v1/warehouses`. | HTTP 401. |
| Read-only protection | Send POST to `/api/shared/stock` with a manager code. | HTTP 405. |
| Revocation | Revoke the link, then repeat a shared request. | HTTP 404. |

When testing manager calls, replace the owner authorization value in a **separate copy** of the test plan. Keep the manager code in the private properties file as `managerCode`, and use `Bearer ${__P(managerCode,)}`. Set the expected response code for each negative test. Keep secrets out of saved screenshots and shared plans.

Restart any stopped services after each failure test:

```sh
docker compose start app-a cache-a cache-b rate-redis
```

## 9. Restore normal limits and stop

```sh
RATE_CAPACITY=60 RATE_REFILL=2 docker compose up -d \
  --no-deps --force-recreate --no-build --pull never app-a app-b
docker compose ps
```

Wait until both apps are healthy, then run:

```sh
docker compose restart nginx
docker compose down
```

`docker compose down` retains the named data volumes. Do not add `-v` if you want to keep inventory and links.

## References and test status

- [Apache JMeter startup and CLI options](https://jmeter.apache.org/usermanual/get-started.html#non_gui).
- [JMeter components and assertions](https://jmeter.apache.org/usermanual/component_reference.html#Response_Assertion).
- [Application API reference](API.md).
- [Earlier application verification](VERIFICATION.md).

The application has earlier live Java/API/browser test results. The new JMeter plan has been checked for XML structure, GET method, and assertion configuration only. JMeter execution, reports, and full Docker startup must be checked on your setup after you install the required software and obtain the images.
