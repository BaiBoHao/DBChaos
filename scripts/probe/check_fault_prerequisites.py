#!/usr/bin/env python3
"""Measure local/mounted server filesystems and the injector's Linux memory budget.

Business row access requires a workload-specific monitor and deliberately fails here.
All output is Java-properties ASCII; diagnostics go to stderr.
"""
import hashlib
import os
from pathlib import Path
import sys


def required(name):
    value = os.environ.get(name)
    if not value:
        raise ValueError('missing ' + name)
    return value


def positive(name):
    value = int(required(name))
    if value <= 0:
        raise ValueError(name + ' must be positive')
    return value


def storage(duration):
    # The operator maps these paths to THIS server; never assume cwd is the DB disk.
    identity = required('DBCHAOS_PREFLIGHT_SERVER_JDBC_URL') + '\n' + required('DBCHAOS_PREFLIGHT_SERVER_DB_USER')
    target = hashlib.sha256(identity.encode('utf-8')).hexdigest()
    if target != required('DBCHAOS_PREFLIGHT_TARGET'):
        raise ValueError('configured server filesystem target differs from JDBC target')
    paths = [Path(required('DBCHAOS_PREFLIGHT_DATA_PATH')),
             Path(required('DBCHAOS_PREFLIGHT_LOG_PATH'))]
    reserve = int(os.environ.get('DBCHAOS_PREFLIGHT_DISK_RESERVE_BYTES', '67108864'))
    if reserve < 0:
        raise ValueError('disk reserve must not be negative')
    available = []
    for path in paths:
        if not path.is_absolute() or not path.is_dir():
            raise ValueError('server path must be an accessible absolute directory: ' + str(path))
        stat = os.statvfs(str(path))
        available.append(max(0, stat.f_bavail * stat.f_frsize - reserve))
    return {
        'target': target,
        'data_available_bytes': available[0],
        'log_available_bytes': available[1],
        'shared_volume': str(paths[0].stat().st_dev == paths[1].stat().st_dev).lower(),
        # Rates must come from calibration for this fault and concurrency, not idle DB traffic.
        'data_growth_bytes': (positive('DBCHAOS_PREFLIGHT_DATA_BYTES_PER_SEC') * duration + 999) // 1000,
        'log_growth_bytes': (positive('DBCHAOS_PREFLIGHT_LOG_BYTES_PER_SEC') * duration + 999) // 1000,
    }


def client_memory():
    values = {}
    for line in Path('/proc/meminfo').read_text().splitlines():
        key, value = line.split(':', 1)
        values[key] = int(value.strip().split()[0]) * 1024
    budget = values['MemAvailable']
    # Respect the current cgroup and ancestor limits, including common cgroup namespaces.
    groups = Path('/proc/self/cgroup').read_text().splitlines()
    candidates = []
    for group in groups:
        _, controllers, suffix = group.split(':', 2)
        if controllers == '':
            root = Path('/sys/fs/cgroup')
            candidates.extend([(root, 'memory.max', 'memory.current'),
                               (root / suffix.lstrip('/'), 'memory.max', 'memory.current')])
        elif 'memory' in controllers.split(','):
            root = Path('/sys/fs/cgroup/memory')
            candidates.extend([(root, 'memory.limit_in_bytes', 'memory.usage_in_bytes'),
                               (root / suffix.lstrip('/'), 'memory.limit_in_bytes', 'memory.usage_in_bytes')])
    for directory, limit_name, used_name in candidates:
        root = Path('/sys/fs/cgroup')
        while directory == root or root in directory.parents:
            limit_path, used_path = directory / limit_name, directory / used_name
            if limit_path.is_file():
                raw = limit_path.read_text().strip()
                if raw != 'max':
                    budget = min(budget, max(0, int(raw) - int(used_path.read_text().strip())))
            if directory == root:
                break
            directory = directory.parent
    return {'target': required('DBCHAOS_PREFLIGHT_TARGET'), 'client_memory_budget_bytes': budget}


def main():
    if len(sys.argv) < 4:
        raise ValueError('usage: checker KIND FAULT DURATION_MS [FAULT_PARAMETERS...]')
    kind, _, duration = sys.argv[1:4]
    duration = int(duration)
    if duration <= 0:
        raise ValueError('duration must be positive')
    if kind == 'storage':
        values = storage(duration)
    elif kind == 'client-memory':
        values = client_memory()
    else:
        raise ValueError('business-hotspots requires a workload-specific row-access monitor; cannot infer it from table traffic')
    print('result=pass')
    for key, value in values.items():
        print('{}={}'.format(key, value))


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('preflight environment check failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)
