"""ARM64-only ad presentation patch, pinned to the supplied 2.4.3 snapshot.
No network/login/playback authorization changes. Reuse original empty-ad paths.
"""
import hashlib,struct
INPUT_SHA256='dbadb2977ad93dded4421e9bc8410988376ef4cc1b98340752cecf1903a32820'
def branch(at,target):
 delta=target-at
 assert delta%4==0 and -(1<<27)<=delta<(1<<27)
 return struct.pack('<I',0x14000000|((delta//4)&0x3ffffff))
def specs():
 return [
  ('cached_advertisement_getter_to_existing_null_path',0x47ad4c,'e1d900a9',branch(0x47ad4c,0x47aec8)),
  ('station_ad_future_to_existing_typed_empty_list',0x807ee4,'e1000054',bytes.fromhex('1f2003d5')),
  ('station_ad_controller_to_existing_empty_completion',0x810fc8,'40030054',branch(0x810fc8,0x811030)),
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
