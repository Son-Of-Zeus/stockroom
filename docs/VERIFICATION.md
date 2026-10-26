# Verification results

Checks were run on 26 October 2026. No software, packages, tools, or container images were installed or downloaded.

## Passing checks

| Check | Result |
| --- | --- |
| Offline compilation | Java sources compile with release 17. |
| Core unit tests | 10 passed: validation, secrets, RESP parsing, and hash-ring behavior. |
| Access tests | 4 passed: trusted proxy handling, owner protection, shared scope restrictions, and retry headers. |
| Live MongoDB tests | 6 passed: concurrent issues, rollback after a destination-credit failure, concurrent idempotent requests, archive rules, concurrent IDs, and low-stock queries. |
| Live Redis tests | 2 passed: burst, refill, capacity, expiry, and atomic enforcement across limiter instances. |
| API scenarios | 8 passed against two live application instances. |
| Cache failure | Both cache nodes were stopped. Autocomplete still returned results through MongoDB. |
| App failure | One app was stopped. The other continued to serve inventory. |
| Rate service failure | Rate-limit Redis was stopped. API requests returned 503. |
| Browser workflow | Owner entry, dashboard, product autocomplete, stock receipt, short-link generation, manager view, and revocation were checked. |
| JavaScript syntax | Both browser scripts pass `node --check`. |
| Python syntax | Script compilation passed using the installed Python. |
| Compose structure | `docker compose config --quiet` passed. |
| Source comments | No comments were added to application, test, script, or infrastructure code. |

The native checks used the installed MongoDB 8.2.5 and Redis executables with fresh temporary data. Both apps ran on the installed OpenJDK 17.0.14 runtime. Maven’s test JVM was the installed Java 27; its compiler target was Java 17. Test services used free loopback ports. Database tests created and removed their own random databases. The harness removed its temporary services and data after each completed run.

The browser was checked at the in-app panel’s narrow viewport. Tables scroll horizontally when needed. The saved [dashboard preview](preview.png) shows the actual application with sample data and synthetic test records.

## Bounded load result

The load check used eight workers and 120 requests, alternating between the two direct native app URLs. Both apps shared the same IP bucket, MongoDB, and Redis services. Nginx was not part of this measured run.

| Metric | Measured result |
| --- | --- |
| Requests | 120 |
| Requests received by each instance | 60 |
| HTTP 200 | 61 |
| HTTP 429 | 59 |
| Elapsed time | 0.666 seconds |
| Successful requests per second during this burst | 91.55 |
| All completed requests per second | 180.10 |
| P50 latency, including rejections | 26.67 ms |
| P95 latency, including rejections | 138.40 ms |

The accepted burst is consistent with an initially full 60-token bucket refilling at two tokens per second. Burst throughput is not sustained throughput. The sustained per-IP allowance is two requests per second after the initial burst. These measurements describe one local run and do not establish production capacity. Raw output is in [load-result.json](load-result.json).

## Checks that need your setup

Executable Spring Boot JAR packaging could not finish offline because the local Maven cache lacks some packaging-plugin dependencies. The application was tested from its compiled classpath instead. Run `mvn -U verify` yourself to obtain missing dependencies and build the executable JAR.

The Docker daemon was not running. No images were pulled and no Docker build was attempted. Compose syntax was checked, but full Docker startup, image builds, and Nginx failover remain to be checked after you obtain the images and start Docker.

Use this sequence after following the README:

```sh
docker compose ps
python3 scripts/seed.py
python3 scripts/integration_test.py
python3 scripts/load_test.py --requests 120 --workers 8
docker compose stop app-a
curl -i http://localhost:8080/health
docker compose start app-a
```

After stopping `app-a`, the health request should succeed through `app-b`. The first request can detect the failed upstream before Nginx selects the survivor. The API checks use the default bucket settings. Wait for refill if another test has exhausted the bucket.

Do not use these measurements as evidence of million-user capacity or end-to-end high availability. The local MongoDB replica set has one member, and rate-limit Redis and Nginx are single services.
