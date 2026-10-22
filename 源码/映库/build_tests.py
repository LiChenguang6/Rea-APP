from build import *
B=BUILD/'test';B.mkdir(exist_ok=True);C=B/'classes';D=B/'dex';C.mkdir(exist_ok=True);D.mkdir(exist_ok=True)
run(BT/'aapt2','link','-I',ANDROID,'--manifest',ROOT/'test-app/AndroidManifest.xml','-A',ROOT/'test-app/assets','-o',B/'resources.apk')
run('java','-jar',TOOLS/'ecj.jar','-1.8','-proc:none','-nowarn','-bootclasspath',ANDROID,'-classpath',str(BT/'core-lambda-stubs.jar')+':'+str(BUILD/'classes.jar'),'-d',C,*sorted((ROOT/'test-app/src').rglob('*.java')))
with zipfile.ZipFile(B/'test.jar','w') as z:
 for f in C.rglob('*.class'):z.write(f,f.relative_to(C))
run('java','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',ANDROID,'--classpath',BUILD/'classes.jar','--output',D,B/'test.jar')
shutil.copy2(B/'resources.apk',B/'unsigned.apk')
with zipfile.ZipFile(B/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:z.write(D/'classes.dex','classes.dex')
run(BT/'zipalign','-f','4',B/'unsigned.apk',B/'aligned.apk')
K=Path('/workspace/shared-download-secrets');run('java','-jar',TOOLS/'root/usr/share/java/apksigner-35.0.2.jar','sign','--ks',K/'shared-download.p12','--ks-key-alias','shared-download','--ks-pass','file:'+str(K/'storepass'),'--v4-signing-enabled','false','--out',B/'tests.apk',B/'aligned.apk')
