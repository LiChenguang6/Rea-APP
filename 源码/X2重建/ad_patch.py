"""ARM64-only ad presentation patch, pinned to the supplied 2.4.3 snapshot.
No network/login/playback authorization changes. Reuse original empty-ad paths.
"""
import hashlib,struct
INPUT_SHA256='dbadb2977ad93dded4421e9bc8410988376ef4cc1b98340752cecf1903a32820'
def branch(at,target,link=False):
 delta=target-at
 assert delta%4==0 and -(1<<27)<=delta<(1<<27)
 return struct.pack('<I',(0x94000000 if link else 0x14000000)|((delta//4)&0x3ffffff))
def specs():
 return [
  ('cached_advertisement_getter_to_existing_null_path',0x47ad4c,'e1d900a9',branch(0x47ad4c,0x47aec8)),
  ('station_ad_future_to_existing_typed_empty_list',0x807ee4,'e1000054',bytes.fromhex('1f2003d5')),
  ('station_ad_controller_to_existing_empty_completion',0x810fc8,'40030054',branch(0x810fc8,0x811030)),
  # Retain queue, update prompt and system notices, excluding promotional windows.
  ('startup_queue_skip_open_screen_ad',0x813e58,'a0070054',branch(0x813e58,0x813f4c)),
  ('startup_queue_skip_official_benefit_promotion',0x8140e0,'e0050054',branch(0x8140e0,0x81419c)),
  ('startup_queue_skip_ad_group',0x8141b4,'61050034',branch(0x8141b4,0x814260)),
  ('startup_queue_skip_vertical_ad_group',0x814278,'810c0034',branch(0x814278,0x814408)),
  ('startup_queue_skip_gift_promotion',0x814474,'60060054',branch(0x814474,0x814540)),
  # Same receiver and original continuation invoked by dismissing the ad.
  ('video_switch_call_original_post_ad_continuation',0x647dc0,'07000094',branch(0x647dc0,0x815c24,True)),
  # Keep the original Timer/disposal and invoke existing skip action on its tick.
  ('launch_timer_mark_skip_attempt_once',0x5a16c0,'61b74091',bytes.fromhex('1f7001f8')),
  ('launch_timer_remove_unused_closure_load',0x5a16c4,'21e047f9',bytes.fromhex('1f2003d5')),
  ('launch_timer_remove_unused_closure_allocation',0x5a16c8,'8a2d0a94',bytes.fromhex('1f2003d5')),
  ('launch_timer_call_original_skip_action',0x5a16d4,'fbadfe97',branch(0x5a16d4,0x7f5e8c,True)),
  ('launch_skip_remove_only_ad_countdown_gate',0x7f5ebc,'410600b5',bytes.fromhex('1f2003d5')),
  ('home_startup_select_video_tab',0x645f14,'22f87fd3',bytes.fromhex('420080d2')),

 ]
def apply(data,abi):
 if abi!='arm64-v8a':return data,[]
 assert hashlib.sha256(data).hexdigest()==INPUT_SHA256,'Unrecognized ad baseline'
 buf=bytearray(data);changes=[]
 for name,at,expected,replacement in specs():
  before=bytes.fromhex(expected)
  assert data[at:at+4]==before,(name,data[at:at+4].hex())
  buf[at:at+4]=replacement
  changes.append(dict(name=name,offset=at,before=before.hex(),after=replacement.hex()))
 return bytes(buf),changes
