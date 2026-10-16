import unittest
from datetime import datetime
from zoneinfo import ZoneInfo
from moomoo_bridge import normalize_tick,session_key
class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.now=int(datetime(2026,9,18,10,0,tzinfo=ZoneInfo('America/New_York')).timestamp()*1000)
        self.row=dict(code='US.NVDA',timestamp=self.now/1000,price=100,sequence=123,type='AUTO_MATCH',push_data_type='REALTIME')
    def test_real_timestamp(self):
        x=normalize_tick(self.row,self.now);self.assertEqual(x['t'],self.now);self.assertTrue(x['session'].endswith(':regular'))
    def test_cache_recovery_and_late_prints_excluded(self):
        for k,v in [('push_data_type','CACHE'),('push_data_type','BYDISCONN'),('type','LATE'),('type','AVERAGE_PRICE')]:
            with self.subTest(k=k,v=v):self.assertIsNone(normalize_tick(dict(self.row,**{k:v}),self.now))
    def test_stale_future_nan(self):
        for patch in [dict(timestamp=(self.now-16000)/1000),dict(timestamp=(self.now+6000)/1000),dict(price=float('nan'))]:
            self.assertIsNone(normalize_tick(dict(self.row,**patch),self.now))
    def test_eastern_fallback_and_session(self):
        row=dict(self.row,timestamp=None,time='2026-09-18 10:00:00');self.assertEqual(normalize_tick(row,self.now)['t'],self.now)
        self.assertNotEqual(session_key(self.now),session_key(self.now-3600000))
if __name__=='__main__':unittest.main()
