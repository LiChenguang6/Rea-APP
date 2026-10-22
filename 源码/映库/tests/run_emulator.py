from pathlib import Path
import subprocess,time
ROOT=Path('/workspace/local-video-player');ADB='/workspace/android-tools/sdk/platform-tools/adb'
def run(*args,timeout=180):
 p=subprocess.run([ADB,*args],capture_output=True,text=True,timeout=timeout);print(p.stdout,p.stderr,flush=True);return p
until=time.monotonic()+480
while time.monotonic()<until:
 try:
  p=subprocess.run([ADB,'shell','getprop','sys.boot_completed'],capture_output=True,text=True,timeout=8)
  if p.stdout.strip()=='1':break
 except subprocess.TimeoutExpired:pass
 time.sleep(5)
else:raise RuntimeError('Emulator boot did not complete')
print('BOOTED',flush=True)
run('shell','settings','put','global','window_animation_scale','0');run('shell','settings','put','global','transition_animation_scale','0');run('shell','settings','put','global','animator_duration_scale','0')
for apk in [ROOT/'output/CineShelf-preview.apk',ROOT/'build/test/tests.apk']:
 p=run('install','-r','--no-streaming',str(apk));assert 'Success' in p.stdout,'Install failed'
run('logcat','-c')
p=run('shell','am','instrument','-w','org.rea.cineshelf.test/org.rea.cineshelf.test.FlowTest',timeout=600)
(ROOT/'test-instrumentation.txt').write_text(p.stdout+p.stderr)
logs=run('logcat','-d','-s','CineShelfTest:V','AndroidRuntime:E');(ROOT/'test-logcat.txt').write_text(logs.stdout+logs.stderr)
assert 'PASS:' in p.stdout,'Instrumentation did not pass'
assert 'EXTERNAL_GRANTED_READ_BYTES=' in logs.stdout,'External granted URI read did not pass'
