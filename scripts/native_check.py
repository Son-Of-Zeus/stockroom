import argparse
import json
import os
import pathlib
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from api_client import Api


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def wait_port(port, process):
    for _ in range(100):
        if process.poll() is not None:
            raise RuntimeError('A test process stopped during startup.')
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.3):
                return
        except OSError:
            time.sleep(.2)
    raise RuntimeError('A test process did not open its port.')


def main():
    parser = argparse.ArgumentParser(description='Check with already installed MongoDB, Redis, Java, and Maven. No downloads.')
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--java', default='java')
    parser.add_argument('--keep', action='store_true')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    for command in [args.maven, args.java, 'mongod', 'redis-server']:
        if not shutil.which(command):
            parser.error('Install this required tool yourself before retrying: ' + command)
    classpath_file = root / 'target/runtime-classpath.txt'
    if not classpath_file.exists():
        import re
        result = subprocess.run([args.maven, '-o', '-X', 'compile'], cwd=root, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit('Offline compilation failed. Run mvn -o compile to see missing dependencies. No download was attempted.')
        match = re.search(r'compilePath = \[(.*?)\]', result.stdout)
        if not match:
            raise SystemExit('Could not obtain the offline runtime classpath.')
        classpath_file.write_text(os.pathsep.join(match.group(1).split(', ')))
    classpath = classpath_file.read_text().strip()
    if not all(pathlib.Path(value).exists() for value in classpath.split(os.pathsep)):
        raise SystemExit('The offline classpath is stale. Delete target/runtime-classpath.txt and retry.')
    processes = []
    logs = []
    with tempfile.TemporaryDirectory(prefix='stockroom-check-') as directory:
        temp = pathlib.Path(directory)
        def launch(name, command, environment=None):
            handle = open(temp / (name + '.log'), 'w')
            logs.append(handle)
            process = subprocess.Popen(command, cwd=root, env=environment, stdout=handle, stderr=subprocess.STDOUT)
            processes.append(process)
            return process
        try:
            ports = [free_port() for _ in range(6)]
            mongo_port, rate_port, cache_a, cache_b, app_a, app_b = ports
            (temp / 'mongo').mkdir()
            mongo = launch('mongo', ['mongod', '--dbpath', str(temp / 'mongo'), '--bind_ip', '127.0.0.1', '--port', str(mongo_port), '--replSet', 'rs0'])
            wait_port(mongo_port, mongo)
            subprocess.run([args.java, '-cp', classpath, str(root / 'scripts/InitReplica.java'), str(mongo_port)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            redis_processes = []
            for name, port in [('rate', rate_port), ('cache-a', cache_a), ('cache-b', cache_b)]:
                process = launch(name, ['redis-server', '--bind', '127.0.0.1', '--port', str(port), '--save', '', '--appendonly', 'no', '--dir', directory])
                wait_port(port, process)
                redis_processes.append(process)
            environment = dict(os.environ)
            environment.update({'OWNER_ACCESS_KEY': secrets.token_urlsafe(32),
                                'MONGODB_URI': f'mongodb://127.0.0.1:{mongo_port}/stockroom?replicaSet=rs0&directConnection=true&serverSelectionTimeoutMS=3000&timeoutMS=10000',
                                'INVENTORY_TEST_MONGO_URI': f'mongodb://127.0.0.1:{mongo_port}/?replicaSet=rs0&directConnection=true&timeoutMS=10000',
                                'INVENTORY_TEST_REDIS': f'127.0.0.1:{rate_port}', 'REDIS_RATE': f'127.0.0.1:{rate_port}', 'REDIS_CACHES': f'127.0.0.1:{cache_a},127.0.0.1:{cache_b}',
                                'PUBLIC_BASE_URL': f'http://127.0.0.1:{app_a}', 'TRUSTED_PROXY_IP': '', 'RATE_CAPACITY': '60', 'RATE_REFILL': '2',
                                'STOCKROOM_URL': f'http://127.0.0.1:{app_a}', 'SECOND_APP_URL': f'http://127.0.0.1:{app_b}'})
            subprocess.run([args.maven, '-o', 'test'], cwd=root, env=environment, check=True)
            apps = []
            for name, port in [('native-a', app_a), ('native-b', app_b)]:
                app_env = dict(environment, INSTANCE_ID=name, PORT=str(port))
                process = launch(name, [args.java, '-cp', classpath, 'com.example.demo.DemoApplication'], app_env)
                wait_port(port, process)
                apps.append(process)
                for _ in range(60):
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=2) as response:
                            if response.status == 200:
                                break
                    except OSError:
                        time.sleep(.3)
                else:
                    raise RuntimeError('Application health did not become ready.')
            subprocess.run([sys.executable, str(root / 'scripts/integration_test.py')], cwd=root, env=environment, check=True)
            api = Api(environment['STOCKROOM_URL'], environment['OWNER_ACCESS_KEY'])
            for process in redis_processes[1:]:
                process.terminate()
                process.wait(timeout=10)
            status, _, _ = api.request('GET', '/api/v1/autocomplete?prefix=uncached')
            assert status == 200, 'Autocomplete did not fall back to MongoDB.'
            print('PASS: both cache nodes stopped; autocomplete fell back to MongoDB.', flush=True)
            apps[0].terminate()
            apps[0].wait(timeout=15)
            second_api = Api(environment['SECOND_APP_URL'], environment['OWNER_ACCESS_KEY'])
            assert second_api.request('GET', '/api/v1/dashboard')[0] == 200
            print('PASS: one app stopped; the other app still serves inventory.', flush=True)
            redis_processes[0].terminate()
            redis_processes[0].wait(timeout=10)
            assert second_api.request('GET', '/api/v1/dashboard', retry=False)[0] == 503
            print('PASS: rate-limit Redis stopped; the API returned HTTP 503.', flush=True)
            if args.keep:
                for name, port in [('rate-restarted', rate_port), ('cache-a-restarted', cache_a), ('cache-b-restarted', cache_b)]:
                    process = launch(name, ['redis-server', '--bind', '127.0.0.1', '--port', str(port), '--save', '', '--appendonly', 'no', '--dir', directory])
                    wait_port(port, process)
                app_env = dict(environment, INSTANCE_ID='native-a', PORT=str(app_a))
                process = launch('native-a-restarted', [args.java, '-cp', classpath, 'com.example.demo.DemoApplication'], app_env)
                wait_port(app_a, process)
                time.sleep(1)
                subprocess.run([sys.executable, str(root / 'scripts/seed.py')], cwd=root, env=environment, check=True)
                runtime = root / '.runtime'
                runtime.mkdir(exist_ok=True)
                state_file = runtime / 'native-check.json'
                state_file.write_text(json.dumps({'url': environment['STOCKROOM_URL'], 'secondUrl': environment['SECOND_APP_URL'],
                                                 'key': environment['OWNER_ACCESS_KEY'], 'directory': directory}))
                state_file.chmod(0o600)
                print('Native preview is ready at ' + environment['STOCKROOM_URL'], flush=True)
                print('Press Ctrl+C to stop the temporary test services.', flush=True)
                try:
                    while True:
                        time.sleep(1)
                finally:
                    state_file.unlink(missing_ok=True)
        except KeyboardInterrupt:
            pass
        except Exception:
            for handle in logs:
                handle.flush()
                content = pathlib.Path(handle.name).read_text(errors='replace')
                if 'Exception' in content or 'ERROR' in content or 'Fatal' in content:
                    print(pathlib.Path(handle.name).name + ':\n' + content[-6000:])
            raise
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
            for process in processes:
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            for handle in logs:
                handle.close()


if __name__ == '__main__':
    main()
