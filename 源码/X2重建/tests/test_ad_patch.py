"""Execute patched ARM64 ad paths on synthetic pointers; no accounts or video sources."""
import sys,struct,hashlib,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ad_patch import apply,specs
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
BASE=Path('/workspace/x2-analysis/updated-decoded/lib/arm64-v8a/libapp.so').read_bytes()
PATCHED,CHANGES=apply(BASE,'arm64-v8a')
STOP=0x900000;STACK=0x1010000;THR=0x1100000;PP=0x1200000

def vm(data):
 u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,(len(data)+0xffff)&~0xffff);u.mem_write(0,data)
 for at,size in [(STOP,0x1000),(STACK-0x10000,0x20000),(THR,0x1000),(PP,0x50000)]:u.mem_map(at,size)
 u.reg_write(UC_ARM64_REG_X15,STACK);u.reg_write(UC_ARM64_REG_X26,THR);u.reg_write(UC_ARM64_REG_X27,PP)
 u.reg_write(UC_ARM64_REG_X29,0x12345678);u.reg_write(UC_ARM64_REG_X30,STOP)
 return u
class AdPaths(unittest.TestCase):
 def test_only_reviewed_instruction_bytes_change(self):
  allowed={a+i for _,a,_,_ in specs() for i in range(4)}
  self.assertTrue(all(a==b or i in allowed for i,(a,b) in enumerate(zip(BASE,PATCHED))))
  self.assertEqual(len(BASE),len(PATCHED));self.assertEqual(len(CHANGES),3)
  with self.assertRaises(AssertionError):apply(PATCHED,'arm64-v8a')
 def test_cached_ad_getter_returns_original_null_and_preserves_frame(self):
  for null in [0x200001,0x100200001,0x7000200001]:
   u=vm(PATCHED);u.reg_write(UC_ARM64_REG_X22,null)
   u.emu_start(0x47ad34,STOP,count=100)
   self.assertEqual(u.reg_read(UC_ARM64_REG_PC),STOP)
   self.assertEqual(u.reg_read(UC_ARM64_REG_X0),null)
   self.assertEqual(u.reg_read(UC_ARM64_REG_X15),STACK)
   self.assertEqual(u.reg_read(UC_ARM64_REG_X29),0x12345678)
 def test_station_ads_always_use_existing_typed_empty_allocation(self):
  for value in [0x200001,0x300001]:
   u=vm(PATCHED);u.reg_write(UC_ARM64_REG_X22,0x200001);u.reg_write(UC_ARM64_REG_X0,value)
   u.mem_write(PP+0x32a48,struct.pack('<Q',0x400001));allocated=[]
   def hook(u,addr,size,_):
    if addr==0x2375bc:
     allocated.append((u.reg_read(UC_ARM64_REG_X1),u.reg_read(UC_ARM64_REG_X2)))
     u.reg_write(UC_ARM64_REG_X0,0x500001);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_X30))
    if addr==0x807f04:u.emu_stop()
   u.hook_add(UC_HOOK_CODE,hook);u.emu_start(0x807ee0,STOP,count=100)
   self.assertEqual(allocated,[(0x400001,0)])
   self.assertEqual(u.reg_read(UC_ARM64_REG_X3),0x500001)
 def test_station_controller_skips_populating_ad_widgets(self):
  u=vm(PATCHED);u.emu_start(0x810fc8,0x811030,count=1)
  self.assertEqual(u.reg_read(UC_ARM64_REG_PC),0x811030)
 def test_other_abis_stay_unchanged(self):
  self.assertEqual(apply(b'unchanged','x86_64'),(b'unchanged',[]))
if __name__=='__main__':unittest.main(verbosity=2)
