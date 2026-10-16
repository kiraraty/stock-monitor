#!/usr/bin/env python3
"""Read-only OpenD bridge. JSON prefixed stdout is consumed by the Go monitor.
No trading contexts, credentials, orders, or writes to the stock analysis project.
"""
import argparse
import json
import math
import os
import queue
import signal
import sys
import threading
import time
from datetime import datetime
from zoneinfo import ZoneInfo

NY = ZoneInfo('America/New_York')
LIVE_STATES = {'MORNING', 'AFTERNOON', 'PRE_MARKET_BEGIN', 'AFTER_HOURS_BEGIN', 'OVERNIGHT'}
# Only eligible ordinary executions. Late, average-price, auction and other
# special-condition prints cannot establish a flash-crash price in this monitor.
ELIGIBLE_TYPES = {'AUTO_MATCH', 'INTER_AUTO_MATCH', 'ODD_LOT'}

def session_key(ts):
    dt = datetime.fromtimestamp(ts / 1000, NY)
    minute = dt.hour * 60 + dt.minute
    segment = 'regular' if 570 <= minute < 960 else 'pre' if 240 <= minute < 570 else 'after' if 960 <= minute < 1200 else 'overnight'
    return f'{dt.date()}:{segment}'

def normalize_tick(row, now):
    """Use exchange timestamp, excluding cached/recovered/special prints."""
    if str(row.get('push_data_type')) != 'REALTIME' or str(row.get('type')) not in ELIGIBLE_TYPES:
        return None
    stamp = row.get('timestamp')
    if stamp is None or not math.isfinite(float(stamp)) or float(stamp) <= 0:
        dt = datetime.fromisoformat(str(row['time'])).replace(tzinfo=NY)
        ts = int(dt.timestamp() * 1000)
    else:
        stamp = float(stamp)
        ts = int(stamp if stamp > 10**12 else stamp * 1000)
    price, seq = float(row['price']), int(row['sequence'])
    if not math.isfinite(price) or price <= 0 or seq <= 0 or now-ts > 15000 or ts-now > 5000:
        return None
    return {'type':'tick','symbol':str(row['code']), 't':ts, 'p':price, 'id':seq, 'session':session_key(ts)}

def emit(obj):
    print('PNALE '+json.dumps(obj, ensure_ascii=False, separators=(',',':'), allow_nan=False), flush=True)

def run():
    parser = argparse.ArgumentParser()
    parser.add_argument('--symbols', required=True)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', default=11111, type=int)
    parser.add_argument('--probe', action='store_true')
    args = parser.parse_args()
    import moomoo as m
    symbols = args.symbols.split(',')
    if len(symbols)>100 or not all(s.startswith('US.') for s in symbols):
        raise ValueError('configured US watchlist must contain at most 100 symbols')
    pending = queue.Queue(maxsize=50000)
    lost = threading.Event()
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_:stop.set())
    signal.signal(signal.SIGINT, lambda *_:stop.set())
    class Handler(m.TickerHandlerBase):
        def on_recv_rsp(self, rsp_pb):
            ret, data = super().on_recv_rsp(rsp_pb)
            if ret != m.RET_OK:
                lost.set();return ret,data
            now = int(time.time()*1000)
            for row in data.to_dict('records'):
                try:
                    item = normalize_tick(row, now)
                    if item:
                        pending.put_nowait(item)
                except queue.Full:
                    lost.set()
                except (ValueError, TypeError, OverflowError):
                    continue
            return ret,data
    q = m.OpenQuoteContext(host=args.host, port=args.port)
    try:
        ret, info = q.get_user_info()
        if ret != m.RET_OK:
            raise RuntimeError('OpenD quote permission query failed')
        right = str(info.get('us_qot_right','unknown'))
        quota = int(info.get('sub_quota',0))
        if right not in {'LV1','LV2','LV3'}:
            raise RuntimeError('US realtime quote permission unavailable: '+right)
        q.set_handler(Handler())
        ret, err = q.subscribe(symbols,[m.SubType.TICKER],is_first_push=False,session=m.Session.ALL)
        if ret != m.RET_OK:
            raise RuntimeError('OpenD ticker subscription rejected: '+str(err)[:250])
        last_status = 0.0
        while not stop.is_set():
            if lost.is_set():
                raise RuntimeError('OpenD push error or local queue overflow; coverage reset required')
            if time.monotonic()-last_status >= 30:
                ret, global_state = q.get_global_state()
                if ret != m.RET_OK or not global_state.get('qot_logined',False):
                    raise RuntimeError('OpenD disconnected or logged out')
                ret, states = q.get_market_state(symbols)
                if ret != m.RET_OK:
                    raise RuntimeError('OpenD market state unavailable')
                emit({'type':'state','time_ms':int(time.time()*1000),'permission':right,'quota':quota,
                      'states':{str(x['code']):str(x['market_state']) for x in states.to_dict('records')},'subscribed':symbols,'sdk':m.__version__})
                last_status = time.monotonic()
                if args.probe:return
            try:
                emit(pending.get(timeout=.2))
            except queue.Empty:
                pass
    finally:
        q.close()

if __name__ == '__main__':
    try:run()
    except Exception as e:
        emit({'type':'error','message':str(e)[:300]})
        sys.exit(1)
