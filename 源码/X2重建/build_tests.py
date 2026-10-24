from build import *
P=R/'test-app';D=B/'tests';D.mkdir(exist_ok=True);C=D/'classes';X=D/'dex';C.mkdir(exist_ok=True);X.mkdir(exist_ok=True)
run(BT/'aapt2','link','-I',S/'android-35/android.jar','--manifest',P/'AndroidManifest.xml','-o',D/'resources.apk')
run('java','-jar',T/'ecj.jar','-1.8','-proc:none','-nowarn','-bootclasspath',S/'android-35/android.jar','-classpath',str(BT/'core-lambda-stubs.jar')+':'+str(B/'classes.jar'),'-d',C,*sorted((P/'src').rglob('*.java')))
with zipfile.ZipFile(D/'classes.jar','w') as z:
 for p in C.rglob('*.class'):z.write(p,p.relative_to(C))
run('java','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','24','--lib',S/'android-35/android.jar','--classpath',B/'classes.jar','--output',X,D/'classes.jar')
shutil.copy2(D/'resources.apk',D/'unsigned.apk')
with zipfile.ZipFile(D/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:z.write(X/'classes.dex','classes.dex')
run(BT/'zipalign','-f','4',D/'unsigned.apk',D/'aligned.apk')
K=Path('/workspace/shared-download-secrets');run('java','-jar',T/'root/usr/share/java/apksigner-35.0.2.jar','sign','--ks',K/'shared-download.p12','--ks-key-alias','shared-download','--ks-pass','file:'+str(K/'storepass'),'--v4-signing-enabled','false','--out',D/'tests.apk',D/'aligned.apk')
