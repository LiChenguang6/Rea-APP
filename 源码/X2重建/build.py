#!/usr/bin/env python3
from pathlib import Path
import subprocess,shutil,zipfile,hashlib,xml.etree.ElementTree as E,json,re
from ad_patch import apply as apply_ad_patch
R=Path(__file__).resolve().parent;T=Path('/workspace/android-tools');S=T/'sdk';BT=S/'android-15';B=R/'build';OUT=R/'output';INPUT=Path('/workspace/x2-analysis/updated-input/base.apk');DECODED=B/'decoded';A='{http://schemas.android.com/apk/res/android}'
E.register_namespace('android',A[1:-1])
def run(*args):subprocess.run(list(map(str,args)),check=True)
def node(parent,tag,**attrs):return E.SubElement(parent,tag,{A+k:str(v) for k,v in attrs.items()})
def compile_dex():
 c=B/'classes';d=B/'dex';c.mkdir(exist_ok=True);d.mkdir(exist_ok=True)
 run('java','-jar',T/'ecj.jar','-1.8','-proc:none','-nowarn','-bootclasspath',S/'android-35/android.jar','-classpath',BT/'core-lambda-stubs.jar','-d',c,*sorted((R/'src').rglob('*.java')))
 with zipfile.ZipFile(B/'classes.jar','w') as z:
  for p in c.rglob('*.class'):z.write(p,p.relative_to(c))
 run('java','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','24','--lib',S/'android-35/android.jar','--output',d,B/'classes.jar')
 return d/'classes.dex'
def inject_at_start(path,signature,call):
 text=path.read_text();start=text.index('.method '+signature+'\n');end=text.index('.end method',start);block=text[start:end];point=block.index('\n',block.index('.locals '));block=block[:point+1]+'\n    '+call+'\n'+block[point+1:];path.write_text(text[:start]+block+text[end:])
def main():
 B.mkdir(exist_ok=True);OUT.mkdir(exist_ok=True)
 assert hashlib.sha256(INPUT.read_bytes()).hexdigest()==json.loads(Path('/workspace/x2-analysis/updated-input/baseline-pin.json').read_text())['sha256']
 shutil.rmtree(DECODED,ignore_errors=True);shutil.copytree('/workspace/x2-analysis/updated-decoded',DECODED,ignore=shutil.ignore_patterns('build'))
 tree=E.parse(DECODED/'AndroidManifest.xml');root=tree.getroot();old=root.get('package');root.set('package','com.example.yinqishe_shared');app=root.find('application');app.set(A+'label','X2社·下载');app.set(A+'debuggable','false')
 for element in root.iter():
  for attr in ['authorities','name']:
   val=element.get(A+attr,'')
   if val.startswith(old+'.') and (attr=='authorities' or element.tag in ['permission','uses-permission']):element.set(A+attr,'com.example.yinqishe_shared'+val[len(old):])
 for permission in ['android.permission.FOREGROUND_SERVICE_DATA_SYNC','android.permission.POST_NOTIFICATIONS','android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS']:
  if not any(n.get(A+'name')==permission for n in root.findall('uses-permission')):node(root,'uses-permission',name=permission)
 node(root,'permission',name='org.x2.shared.permission.DOWNLOAD',protectionLevel='signature');node(root,'uses-permission',name='org.x2.shared.permission.DOWNLOAD')
 for name in ['CacheExportActivity','PlayingExportActivity','DownloadTasksActivity','DownloadSettingsActivity','VideoPreviewActivity','AppearanceActivity','SharedDownloadActivity','LocalPlaybackActivity']:
  attrs=dict(name='org.x2.export.'+name,exported='false',theme='@android:style/Theme.Material.Light.NoActionBar',configChanges='orientation|screenSize|keyboardHidden')
  if name in ['SharedDownloadActivity','LocalPlaybackActivity']:attrs.update(exported='true',permission='org.x2.shared.permission.DOWNLOAD')
  node(app,'activity',**attrs)
 node(app,'service',name='org.x2.export.DownloadService',exported='false',stopWithTask='false',foregroundServiceType='dataSync')
 node(app,'provider',name='org.x2.export.SharedDownloadGateway',authorities='com.example.yinqishe_shared.shared-download',exported='true',permission='org.x2.shared.permission.DOWNLOAD')
 tree.write(DECODED/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
 p=DECODED/'apktool.yml';s=p.read_text();s=re.sub(r'(?m)^  versionCode:.*','  versionCode: 33',s);s=re.sub(r'(?m)^  versionName:.*','  versionName: 2.4.3-download-refresh3-noads',s);p.write_text(s)
 main=DECODED/'smali_classes4/com/hr/xksilver/tq/MainActivity.smali';s=main.read_text();needle='    invoke-static {}, Lio/flutter/embedding/android/FlutterActivity;->convertUnit()V';assert s.count(needle)==1;s=s.replace(needle,needle+'\n\n    invoke-static {v0}, Lorg/x2/export/ExportEntry;->install(Landroid/app/Activity;)V');main.write_text(s)
 plugin=DECODED/'smali_classes4/com/sarthak/better_player_enhanced/BetterPlayerPlugin.smali';s=plugin.read_text();start=s.index('.method private final setDataSource(');end=s.index('.end method',start);block=s[start:end];needle='    move-object/from16 v3, p3';assert block.count(needle)>=1;block=block.replace(needle,needle+'\n\n    invoke-static {v3, v1}, Lorg/x2/export/PlayerCapture;->bind(Ljava/lang/Object;Ljava/util/Map;)V',1);plugin.write_text(s[:start]+block+s[end:])
 player=DECODED/'smali_classes4/com/sarthak/better_player_enhanced/BetterPlayer.smali'
 for method,hook in [('play','markPlaying'),('pause','markPaused'),('dispose','dispose')]:inject_at_start(player,'public final '+method+'()V','invoke-static/range {p0 .. p0}, Lorg/x2/export/PlayerCapture;->'+hook+'(Ljava/lang/Object;)V')
 ad_changes=[]
 for lib in (DECODED/'lib').glob('*/libapp.so'):
  patched,changes=apply_ad_patch(lib.read_bytes(),lib.parent.name);lib.write_bytes(patched)
  ad_changes.extend(dict(abi=lib.parent.name,**change) for change in changes)
 dex=compile_dex()
 # Add a new dex, without replacing any of the supplied player's dex files.
 existing=[int(m.group(1) or '1') for p in DECODED.glob('classes*.dex') if (m:=re.fullmatch(r'classes(\d*)\.dex',p.name))]
 # Apktool decodes originals into smali dirs; classes9 is outside their indices.
 with zipfile.ZipFile(INPUT) as z:
  indices=[int(m.group(1) or '1') for name in z.namelist() if (m:=re.fullmatch(r'classes(\d*)\.dex',name))]
 next_dex='classes'+str(max(indices)+1)+'.dex'
 shutil.copy2(dex,DECODED/next_dex)
 # Only the existing remuxer is carried forward. Never load the old player-offset JNI library.
 with zipfile.ZipFile('/workspace/shared-download-integration/output/X2-shared-download.apk') as z:
  for n in z.namelist():
   if n.startswith('lib/') and n.endswith('/libx2export.so'):
    p=DECODED/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(n))
 run('java','-jar',T/'apktool.jar','b','-p',T/'framework',DECODED,'-o',B/'unsigned.apk')
 run(BT/'zipalign','-f','-p','4',B/'unsigned.apk',B/'aligned.apk')
 key=Path('/workspace/shared-download-secrets');final=OUT/'X2-download-refresh3-noads.apk'
 run('java','-jar',T/'root/usr/share/java/apksigner-35.0.2.jar','sign','--ks',key/'shared-download.p12','--ks-key-alias','shared-download','--ks-pass','file:'+str(key/'storepass'),'--v4-signing-enabled','false','--out',final,B/'aligned.apk')
 run('java','-jar',T/'root/usr/share/java/apksigner-35.0.2.jar','verify','--verbose',final)
 with zipfile.ZipFile(INPUT) as oldzip,zipfile.ZipFile(final) as newzip:
  names=[n for n in oldzip.namelist() if n.startswith(('lib/','assets/')) and not n.endswith('/')]
  for n in names:
   expected=oldzip.read(n)
   if n.endswith('/libapp.so'):expected,_=apply_ad_patch(expected,n.split('/')[1])
   assert expected==newzip.read(n),'Unexpected native or asset change: '+n
 report={'input_sha256':hashlib.sha256(INPUT.read_bytes()).hexdigest(),'apk_sha256':hashlib.sha256(final.read_bytes()).hexdigest(),'assets_and_non_ad_native_bytes_identical':True,'ad_presentation_patches':ad_changes,'ad_patch_abis':['arm64-v8a'],'legacy_offset_patch':False,'original_login_and_entitlement_native_code':'unchanged','package':'com.example.yinqishe_shared','versionCode':33,'read_only_java_capture':'BetterPlayer data source plus play/pause/dispose'}
 (OUT/'static-audit.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
if __name__=='__main__':main()
