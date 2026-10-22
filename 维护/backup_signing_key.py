#!/usr/bin/env python3
"""Owner policy: signing materials only; no full workspace snapshots."""
from pathlib import Path
import os,secrets,subprocess,hashlib,json
SECRET=Path('/workspace/shared-download-secrets')
PASSWORD=Path(os.environ.get('REA_KEY_BACKUP_PASSWORD_FILE','/tmp/rea-app-private/key-backup-password.txt'))
DEST=Path('/workspace/rea-app-publish/密钥备份')
SEVEN='/workspace/backup-tools/root/usr/lib/7zip/7z'
def main():
 PASSWORD.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
 if not PASSWORD.exists():
  previous=PASSWORD.parent/'backup-password.txt'
  PASSWORD.write_text(previous.read_text() if previous.exists() else secrets.token_urlsafe(32)+'\n');PASSWORD.chmod(0o600)
 required=[SECRET/'shared-download.p12',SECRET/'storepass']
 if not all(p.is_file() for p in required):raise RuntimeError('Signing key or its password is missing; refusing an incomplete backup')
 DEST.mkdir(parents=True,exist_ok=True)
 note=SECRET/'SIGNING-KEY-INFO.txt';note.write_text('PKCS12: shared-download.p12\nAlias: shared-download\nKeystore/key password: storepass file\nThis is the NEW signing key used for the shared-download variants and CineShelf (org.rea.cineshelf).\nThe original export21 signing private key was never recovered and is NOT contained here.\nKeep these files private. APK changes still require rebuilding/repacking and signing with the original package identity.\n')
 output=DEST/'Android-signing-key.7z';temporary=DEST/'Android-signing-key.pending.7z'
 if temporary.exists():temporary.unlink()
 password=PASSWORD.read_text().strip()
 if len(password)<24:raise RuntimeError('Backup password is too short')
 result=subprocess.run([SEVEN,'a','-t7z','-mx=5','-mhe=on','-p',str(temporary),*[p.name for p in required],note.name],cwd=SECRET,input=password+'\n'+password+'\n',text=True,capture_output=True)
 if result.returncode:raise RuntimeError('Key archive creation failed')
 tested=subprocess.run([SEVEN,'t',str(temporary)],input=password+'\n',text=True,capture_output=True)
 if tested.returncode:raise RuntimeError('Encrypted key archive integrity check failed')
 temporary.replace(output)
 digest=hashlib.sha256(output.read_bytes()).hexdigest()
 (DEST/'SHA256SUMS.txt').write_text(digest+'  '+output.name+'\n')
 (DEST/'README.md').write_text('''# Android 签名密钥备份

仅备份签名材料，不含整个环境。压缩包已加密内容与文件名，解密密码不在 GitHub，由用户私下保存。

解密后包含新 PKCS12 私钥、其密码文件和 alias 说明，可用于给修改后的独立播放器及共享下载变体继续签名，保持这些变体的更新兼容性。

**不包含旧 export21 的原签名私钥**；它从未恢复。密钥用于签名，不代替修改 APK 所需的代码或工具。请保留应用包名，并为后续版本增加版本号。

只有密钥变化或首次备份时才需要生成新密钥备份；普通 APK 构建不需要重复备份整个环境。禁止把解密后的私钥或密码提交到公开仓库。
''')
 print('Encrypted signing-key backup verified:',output.name,output.stat().st_size,'bytes; SHA256',digest)
if __name__=='__main__':main()
