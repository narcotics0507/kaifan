#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,re,sys,zipfile
CACHE=Path('/apps/kaifan/cache/jar-libraries')

def seed(jar,cache=CACHE):
 cache.mkdir(parents=True,exist_ok=True);cache.chmod(0o700)
 with zipfile.ZipFile(jar) as source:
  for name in source.namelist():
   if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
    data=source.read(name);target=cache/(hashlib.sha256(data).hexdigest()+'.jar')
    if not target.exists():target.write_bytes(data);target.chmod(0o600)

def restore(root,cache=CACHE):
 root=Path(root);cache.mkdir(parents=True,exist_ok=True)
 manifest=json.loads((root/'dependency-manifest.json').read_text());seen=set()
 for entry in manifest:
  name=entry['name'];digest=entry['sha256']
  if not re.fullmatch(r'BOOT-INF/lib/[A-Za-z0-9._+-]+\.jar',name) or name in seen or not re.fullmatch('[a-f0-9]{64}',digest):raise ValueError('Invalid dependency manifest')
  seen.add(name);file=cache/(digest+'.jar');incoming=root/'libraries'/(digest+'.jar')
  if not file.exists():
   data=incoming.read_bytes()
   if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Dependency checksum mismatch')
   file.write_bytes(data);file.chmod(0o600)
  if hashlib.sha256(file.read_bytes()).hexdigest()!=digest:raise ValueError('Cached dependency checksum mismatch')
 temp=root/'backend-restored.jar'
 with zipfile.ZipFile(root/'backend.jar') as source,zipfile.ZipFile(temp,'w') as target:
  for entry in source.infolist():
   if entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):raise ValueError('Expected thin backend archive')
   target.writestr(entry,source.read(entry.filename))
  for entry in manifest:
   target.writestr(entry['name'],(cache/(entry['sha256']+'.jar')).read_bytes(),compress_type=zipfile.ZIP_STORED)
 temp.replace(root/'backend.jar')

if __name__=='__main__':
 if len(sys.argv)==1:
  print(json.dumps([p.stem for p in CACHE.glob('*.jar') if re.fullmatch('[a-f0-9]{64}',p.stem)]))
 elif sys.argv[1]=='seed':seed(sys.argv[2])
 elif sys.argv[1]=='restore':restore(sys.argv[2])
 else:raise SystemExit(64)
