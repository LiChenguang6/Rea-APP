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
  self.assertEqual(len(BASE),len(PATCHED));self.assertEqual(len(CHANGES),14)
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

 def test_promotion_queue_skips_to_original_next_slots(self):
  for at,target in [(0x813e58,0x813f4c),(0x8140e0,0x81419c),(0x8141b4,0x814260),(0x814278,0x814408),(0x814474,0x814540)]:
   u=vm(PATCHED);u.emu_start(at,target,count=1)
   self.assertEqual(u.reg_read(UC_ARM64_REG_PC),target)
  # Upgrade dialog and system announcement construction remain byte-identical.
  for a,b in [(0x813d18,0x813dd8),(0x813f4c,0x8140a4),(0x814540,0x814608)]:
   self.assertEqual(BASE[a:b],PATCHED[a:b])
 def test_video_switch_preserves_receiver_and_runs_original_continuation(self):
  for data,expected in [(BASE,0x647ddc),(PATCHED,0x815c24)]:
   u=vm(data);u.reg_write(UC_ARM64_REG_X22,0x200001);u.reg_write(UC_ARM64_REG_X1,0x330001);calls=[]
   def hook(u,addr,size,_):
    if addr in [0x648c30,0x82ccf0,0x647ddc,0x815c24]:
     calls.append((addr,u.reg_read(UC_ARM64_REG_X1)))
     u.reg_write(UC_ARM64_REG_X0,0x440001)
     u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_X30))
   u.hook_add(UC_HOOK_CODE,hook);u.emu_start(0x647d80,STOP,count=150)
   self.assertEqual(calls[-1],(expected,0x330001))
   self.assertEqual(u.reg_read(UC_ARM64_REG_PC),STOP)
   self.assertEqual(u.reg_read(UC_ARM64_REG_X15),STACK)
  # Original video-detail/authentication and player setup are preserved.
  for a,b in [(0x800580,0x8008d0),(0x815c24,0x815f44)]:
   self.assertEqual(BASE[a:b],PATCHED[a:b])
 def test_launch_timer_invokes_original_skip_with_correct_state(self):
  u=vm(PATCHED);u.mem_map(0x1300000,0x10000)
  closure,context,state=0x1300001,0x1300101,0x1300201
  for a,v in [(STACK+8,closure),(closure+0x17,context),(context+0xf,state),(state+0x17,5)]:
   u.mem_write(a,struct.pack('<Q',v))
  u.reg_write(UC_ARM64_REG_X28,0);u.reg_write(UC_ARM64_REG_X22,0x200001);calls=[]
  def hook(u,addr,size,_):
   if addr in [0x82ccf0,0x7f5e8c]:
    calls.append((addr,u.reg_read(UC_ARM64_REG_X1)))
    u.reg_write(UC_ARM64_REG_X0,0x440001)
    u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_X30))
  u.hook_add(UC_HOOK_CODE,hook);u.emu_start(0x5a1678,STOP,count=150)
  self.assertEqual(calls[-1],(0x7f5e8c,state))
  self.assertEqual(len(calls),1)
  self.assertEqual(u.mem_read(state+0x17,8),bytes(8))
  # Next periodic tick cannot repeat navigation or initialization-failure toast.
  u.reg_write(UC_ARM64_REG_X30,STOP);calls.clear()
  u.emu_start(0x5a1678,STOP,count=150)
  self.assertEqual(calls,[])

  self.assertEqual(u.reg_read(UC_ARM64_REG_X15),STACK)
  self.assertEqual(BASE[0x8013a4:0x801480],PATCHED[0x8013a4:0x801480])
 def test_countdown_bypass_keeps_initialization_failure_checks(self):
  u=vm(PATCHED);u.mem_map(0x1300000,0x10000);state=0x1300201
  u.reg_write(UC_ARM64_REG_X29,STACK);u.mem_write(STACK-0x10,struct.pack('<Q',state));u.mem_write(state+0x17,struct.pack('<Q',5))
  u.emu_start(0x7f5eb4,0x7f5ec0,count=10)
  self.assertEqual(u.reg_read(UC_ARM64_REG_PC),0x7f5ec0)
  self.assertEqual(BASE[0x7f5ec0:0x7f5f94],PATCHED[0x7f5ec0:0x7f5f94])
 def test_other_abis_stay_unchanged(self):
  self.assertEqual(apply(b'unchanged','x86_64'),(b'unchanged',[]))
if __name__=='__main__':unittest.main(verbosity=2)
