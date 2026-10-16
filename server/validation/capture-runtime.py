"""Run with system Python on the monitoring host; read-only, prints one JSON object."""
import collections
import hashlib
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request

base = 'http://127.0.0.1:8797'
def get(route):
    try:
        r = urllib.request.urlopen(base + route, timeout=8)
    except urllib.error.HTTPError as e:
        r = e
    with r:
        return {'http_status': r.code, 'body': json.load(r)}

daily = []
ids = set()
kinds = collections.Counter()
sources = collections.Counter()
news_eligible = collections.Counter()
duplicates = 0
for p in sorted(pathlib.Path('/var/lib/pnale-monitor').glob('events-*.jsonl')):
    rows = [json.loads(line) for line in p.read_text().splitlines()]
    daily.append({'file': p.name, 'records': len(rows), 'bytes': p.stat().st_size})
    for e in rows:
        duplicates += e['id'] in ids
        ids.add(e['id'])
        kinds[e['kind']] += 1
        sources[e['source']] += 1
        if e['kind'] in ('news', 'important_news'):
            news_eligible[str(e.get('alert_eligible', False))] += 1
raw = subprocess.check_output(['systemctl', 'show', 'pnale-monitor', '-p', 'ActiveState', '-p', 'SubState', '-p', 'NRestarts', '-p', 'MemoryCurrent', '-p', 'CPUUsageNSec', '-p', 'MainPID', '-p', 'ExecMainStartTimestamp'], text=True)
service = dict(line.split('=', 1) for line in raw.splitlines() if '=' in line)
stat = pathlib.Path('/opt/pnale-monitor/current/pnale-monitor')
print(json.dumps({
    'captured_at_ms': int(time.time() * 1000),
    'health': get('/healthz'),
    'events': get('/v1/events?limit=1000'),
    'rules': get('/v1/rules'),
    'service': service,
    'release': str(pathlib.Path('/opt/pnale-monitor/current').resolve()),
    'binary_sha256': hashlib.sha256(stat.read_bytes()).hexdigest(),
    'config_sha256': hashlib.sha256(pathlib.Path('/etc/pnale-monitor/config.json').read_bytes()).hexdigest(),
    'persisted': {'files': daily, 'unique_ids': len(ids), 'duplicate_ids': duplicates, 'kinds': kinds, 'sources': sources, 'news_alert_eligible': news_eligible},
}, ensure_ascii=False, indent=2))
