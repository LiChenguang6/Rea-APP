#!/usr/bin/env python3
from pathlib import Path
import subprocess,zipfile,shutil,hashlib,json
ROOT=Path(__file__).resolve().parent;BUILD=ROOT/'build';OUT=ROOT/'output';TOOLS=Path('/workspace/android-tools');SDK=TOOLS/'sdk';BT=SDK/'android-15';ANDROID=SDK/'android-35/android.jar'
def run(*a):subprocess.run(list(map(str,a)),check=True)
def main():
 BUILD.mkdir(exist_ok=True);OUT.mkdir(exist_ok=True)
 classes=BUILD/'classes';dex=BUILD/'dex';gen=BUILD/'gen'
 for p in [classes,dex,gen]:shutil.rmtree(p,ignore_errors=True);p.mkdir()
 run(BT/'aapt2','compile','--dir',ROOT/'res','-o',BUILD/'resources.zip')
 run(BT/'aapt2','link','-I',ANDROID,'--manifest',ROOT/'AndroidManifest.xml','--java',gen,'-o',BUILD/'resources.apk',BUILD/'resources.zip')
 run('java','-jar',TOOLS/'ecj.jar','-1.8','-proc:none','-nowarn','-bootclasspath',ANDROID,'-classpath',BT/'core-lambda-stubs.jar','-d',classes,*sorted((ROOT/'src').rglob('*.java')),*sorted(gen.rglob('*.java')))
 with zipfile.ZipFile(BUILD/'classes.jar','w') as z:
  for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes))
 run('java','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',ANDROID,'--output',dex,BUILD/'classes.jar')
 unsigned=BUILD/'unsigned.apk';shutil.copy2(BUILD/'resources.apk',unsigned)
 with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
 run(BT/'zipalign','-f','-p','4',unsigned,BUILD/'aligned.apk')
 final=OUT/'CineShelf-preview.apk';key=Path('/workspace/shared-download-secrets')
 run('java','-jar',TOOLS/'root/usr/share/java/apksigner-35.0.2.jar','sign','--ks',key/'shared-download.p12','--ks-key-alias','shared-download','--ks-pass','file:'+str(key/'storepass'),'--v4-signing-enabled','false','--out',final,BUILD/'aligned.apk')
 run('java','-jar',TOOLS/'root/usr/share/java/apksigner-35.0.2.jar','verify','--verbose',final)
 digest=hashlib.sha256(final.read_bytes()).hexdigest();(OUT/'SHA256SUMS.txt').write_text(digest+'  '+final.name+'\n');print('APK',final,'SHA256',digest)
if __name__=='__main__':main()
