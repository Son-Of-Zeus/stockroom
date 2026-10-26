# Stockroom

Stockroom is a multi-warehouse inventory system. It uses Spring Boot 4.1.2, Java 17, MongoDB, Redis, and a small HTML/CSS/JavaScript frontend.

The owner can manage inventory. Warehouse managers get a short, read-only link for one warehouse. The system includes a distributed token bucket, consistent hashing, a key-value cache, increasing IDs, and autocomplete.

## Software and downloads

No software, packages, tools, or container images were installed for this implementation. Offline checks used libraries and tools already present on the computer. The agent did not run Docker builds or pulls.

**You must run installation and download commands yourself.** Docker Compose uses `pull_policy: never`, so starting services will not fetch missing service images. The Docker build below can fetch base images and Maven dependencies; it is a command for you to run.

For the container setup, install Docker Desktop from [Docker](https://docs.docker.com/desktop/). Python 3 is optional for sample data, API checks, and the load test. For a local Java build, install [JDK 17](https://adoptium.net/temurin/releases/?version=17) and [Maven](https://maven.apache.org/install.html). Native MongoDB and Redis installations are not needed for the Docker setup.

Check your tools:

```sh
docker version
docker compose version
python3 --version
java -version
mvn -version
```

## Start the Docker setup

Run commands from this `demo` directory.

1. Copy the settings file:

```sh
cp .env.example .env
```

2. Set `OWNER_ACCESS_KEY` in `.env` to your own random secret with at least 24 characters. There is no default key. Keep `.env` private. The owner enters this key when opening the workspace.
3. Obtain the images yourself:

```sh
docker pull mongo:8.0
docker pull redis:8-alpine
docker pull nginx:1.28-alpine
docker pull maven:3.9-eclipse-temurin-17
docker pull eclipse-temurin:17-jre
```

4. Build and start:

```sh
docker compose build
docker compose up -d --no-build --pull never
docker compose ps
```

Open [Stockroom](http://localhost:8080). Health checks allow time for MongoDB to become a replica-set primary and for the apps to start. Only Nginx is exposed to the host. MongoDB and Redis stay inside the Docker network.

Set the owner key in your terminal before running scripts. This command asks for it without putting it into shell history:

```sh
read -rs OWNER_ACCESS_KEY
export OWNER_ACCESS_KEY
```

Add sample data:

```sh
python3 scripts/seed.py
```

The seed creates a supplier, two warehouses, eight products, and opening receipts. It uses fixed receipt keys. Running it again does not add the opening stock twice. It does not reset later stock changes. If you archive the sample records, use a new disposable database before reseeding.

Stop services and retain data:

```sh
docker compose down
```

The `mongo-data` volume holds inventory and links. The `rate-data` volume holds token-bucket state. The caches are disposable. Do not remove volumes if you need to retain this data.

## Use the workspace

- Add a supplier, then add products that use that supplier.
- Add warehouses. Use **Stock action** to receive, issue, or transfer whole units.
- Search products in the stock form. Prefixes with two or more characters use the autocomplete API and hash-routed cache.
- Use **Inventory** for live balances and low-stock filters. Low stock means a tracked balance is at or below its product’s reorder level. An untracked product–warehouse pair has no balance and does not create an alert until stock is first received.
- Use **Movements** for history. A transfer is recorded once and appears in both relevant warehouse views.
- Archive products and warehouses only after stock reaches zero. Archive a supplier only after active products have been reassigned or archived.
- Use **Lock workspace** to clear the owner key from application memory. Reloading the page also clears it.

### Warehouse links

Open **Warehouses → Share link**, enter a label, and generate a link. Copy the new link before closing the dialog. Only a SHA-256 hash of the random 128-bit code is stored. An existing link cannot be reconstructed from the list; create a replacement if you lose it.

A manager can see the warehouse name, code, location, stock, low-stock items, and relevant movement history. The manager cannot write stock, see the master catalog, list warehouses, or choose another warehouse. Shared transfer history hides the other warehouse’s identity and operational notes.

Links remain active until revoked. Each shared API request checks MongoDB. Revocation blocks the next request; it cannot erase data that someone has already read or copied. The browser does not persist shared responses in its cache. Short links redirect to a fragment-based manager page, and subsequent data requests send the code in the authorization header.

The default link address is `http://localhost:8080`. It works on the host computer. To use another device on a trusted local network, you must set `HOST_BIND=0.0.0.0` and set `PUBLIC_BASE_URL` to the host’s reachable origin, then recreate the app and proxy containers. The UI does not enable public hosting. Use HTTPS before sending owner keys or manager links across an untrusted network. The owner can create and revoke links only with the owner key.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `OWNER_ACCESS_KEY` | None | Required owner secret, at least 24 characters. |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Origin used for new short links. No path or credentials. |
| `HOST_BIND` | `127.0.0.1` | Docker host binding for Nginx. |
| `MONGODB_URI` | Local `rs0` connection | MongoDB URI; Compose sets its internal address. |
| `REDIS_RATE` | `127.0.0.1:6379` | Shared rate-limiter endpoint. |
| `REDIS_CACHES` | `127.0.0.1:6380,127.0.0.1:6381` | Comma-separated cache endpoints. |
| `RATE_CAPACITY` | `60` | Token bucket maximum and initial burst. |
| `RATE_REFILL` | `2` | Tokens added per second. |
| `TRUSTED_PROXY_IP` | Empty | The only peer allowed to supply `X-Real-IP`. Compose uses the fixed Nginx IP. |
| `INSTANCE_ID` | `local` | Instance label in responses and diagnostics. |
| `PORT` | `8080` | App port. |
| `BIND_ADDRESS` | `127.0.0.1` | App binding; Compose uses `0.0.0.0`. |

The Docker network uses `172.30.78.0/24`. If that subnet conflicts with another network, change it and the trusted Nginx address together.

## Build and test

These commands use only cached Maven dependencies:

```sh
mvn -o test
mvn -o package
```

If Maven reports a missing dependency, the agent must stop that check. You can obtain missing dependencies yourself:

```sh
mvn -U verify
```

The first implementation run could compile and test offline. Executable-JAR packaging was blocked by missing cached dependencies for Spring Boot’s packaging plugin, including `spring-boot-buildpack-platform:4.1.2`, `spring-boot-loader-tools:4.1.2`, `micrometer-observation:1.16.8`, `commons-compress:1.26.2`, and `commons-codec:1.17.0`. The command above resolves them when you run it. Java compilation targets 17. The application was also exercised on the installed Java 17 runtime.

Run API checks against a disposable running system:

```sh
python3 scripts/integration_test.py
```

These checks add uniquely named test fixtures and stock history. They do not remove your data. `SECOND_APP_URL` can point to a second direct app URL; with Docker, the proxy URL exercises the shared system. The checks expect the default bucket settings.

Run the optional database tests against your own disposable replica set:

```sh
export INVENTORY_TEST_MONGO_URI='mongodb://127.0.0.1:27017/?replicaSet=rs0&directConnection=true'
export INVENTORY_TEST_REDIS='127.0.0.1:6379'
mvn -o test
```

Database tests create and drop only a random `stockroom_test_*` database. Redis tests use random bucket keys with expiry. Without these variables, the live dependency tests are skipped.

If MongoDB, Redis, Maven, and Java are already installed, this complete native check uses temporary folders and free local ports. It does not install or download software:

```sh
python3 scripts/native_check.py --maven /absolute/path/to/mvn --java /absolute/path/to/java
```

It starts an isolated replica set, three Redis servers, and two apps; runs offline tests; checks cache fallback, app survival, and rate-limiter failure; then stops its services and deletes its temporary data. `--keep` keeps the test preview open until Ctrl+C. Its ignored `.runtime/native-check.json` contains an ephemeral test key, has owner-only file permissions, and is removed on exit. No production key is used.

Run a bounded load test:

```sh
python3 scripts/load_test.py --requests 120 --workers 8
```

HTTP 429 is expected when the token bucket is exhausted. The output separates successful throughput from total completed throughput. Use `--second-url http://127.0.0.1:8081` to alternate requests between two direct instances when you have configured those ports. With Docker, use the default Nginx URL. See [verification results](docs/VERIFICATION.md), [API reference](docs/API.md), [system design](docs/ARCHITECTURE.md), and [proposed contributions](docs/CONTRIBUTIONS.md).

For setup and Apache JMeter tests, use the [JMeter guide](docs/JMETER.md) and the ready-to-open [read test plan](tests/jmeter/inventory-read.jmx). JMeter is not installed by this project.
