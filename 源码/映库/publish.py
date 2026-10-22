#!/usr/bin/env python3
"""Stage public artifacts only. Signing materials and private backups never enter source zip."""
from pathlib import Path
import shutil,zipfile,hashlib
R=Path(__file__).resolve().parent;P=Path('/workspace/rea-app-publish');D=P/'下载文件夹'
D.mkdir(exist_ok=True)
shutil.copy2(R/'output/CineShelf-preview.apk',D/'CineShelf-preview.apk')
shutil.copy2(R/'README.md',D/'映库-使用说明.md');shutil.copy2(R/'VALIDATION.md',D/'映库-验证记录.md')
with zipfile.ZipFile(D/'CineShelf-source.zip','w',zipfile.ZIP_DEFLATED) as z:
 for folder in ['src','res','tests','test-app']:
  for f in (R/folder).rglob('*'):
   if f.is_file() and '__pycache__' not in f.parts:z.write(f,Path('CineShelf')/f.relative_to(R))
 for name in ['AndroidManifest.xml','build.py','build_tests.py','publish.py','README.md','VALIDATION.md','AGENTS.md','core-test-results.txt','signature.txt','permissions.txt','test-instrumentation.txt','test-logcat.txt','test-cold-restore.txt','test-real-folder.txt']:
  if (R/name).exists():z.write(R/name,Path('CineShelf')/name)
source=P/'源码/映库';source.mkdir(parents=True,exist_ok=True)
for folder in ['src','res','tests','test-app']:
 shutil.copytree(R/folder,source/folder,dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__'))
for name in ['AndroidManifest.xml','build.py','build_tests.py','publish.py','README.md','VALIDATION.md','AGENTS.md']:
 shutil.copy2(R/name,source/name)
shots=D/'映库-界面';shots.mkdir(exist_ok=True)
for f in (R/'screenshots').glob('*.png'):shutil.copy2(f,shots/f.name)
sums=D/'SHA256SUMS.txt';lines=sums.read_text().splitlines() if sums.exists() else []
lines=[line for line in lines if not any(line.endswith(n) for n in ['CineShelf-preview.apk','CineShelf-source.zip'])]
for name in ['CineShelf-preview.apk','CineShelf-source.zip']:lines.append(hashlib.sha256((D/name).read_bytes()).hexdigest()+'  '+name)
sums.write_text('\n'.join(lines)+'\n')
readme=P/'README.md';previous=readme.read_text();title='## 历史共享下载候选版'
if title in previous:previous=previous[previous.index(title)+len(title):].lstrip()
else:previous=previous.replace('# Rea-APP\n','',1).lstrip()
previous=previous.split('## 环境恢复备份')[0].rstrip()+'\n'
readme.write_text('''# Rea-APP

## 映库 · 独立本地播放器（当前开发方向）

选择多个视频目录，整理封面、合并完整重复的显示、保存播放进度、手动核验清理副本。转码相似视频仅提示供人工核对。可与旧 X2 / 51 App 并列安装。

- [下载映库 APK](https://github.com/LiChenguang6/Rea-APP/raw/refs/heads/main/%E4%B8%8B%E8%BD%BD%E6%96%87%E4%BB%B6%E5%A4%B9/CineShelf-preview.apk)
- [使用说明与限制](下载文件夹/映库-使用说明.md)
- [实际验证记录](下载文件夹/映库-验证记录.md)
- [源码压缩包](下载文件夹/CineShelf-source.zip) / [在线查看源码](源码/映库/)
- [界面截图](下载文件夹/映库-界面/)

Android 8.0+，独立包名 `org.rea.cineshelf`，不申请联网权限。当前为 0.1.0 测试版，未在 Redmi Turbo 4 Pro 实机验证。

虚拟影库通过系统文件选择器和内容 URI 共享同一份真实文件。它不能透明替换任意旧 App 的原文件路径，也不保证阻止旧下载器再次下载。删除真实副本会使原路径失效；默认只合并显示，不删除视频。

'''+title+'\n\n'+previous)
print('Staged public player artifacts; no signing/password files included')
