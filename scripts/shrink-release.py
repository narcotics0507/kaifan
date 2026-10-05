#!/usr/bin/env python3
"""Reuse verified server-side Maven libraries; send only changed application content."""
from pathlib import Path
import sys,json,zipfile,hashlib,tempfile,tarfile,shutil

def prepare(archive,output,available):
 with tempfile.TemporaryDirectory() as tmp:
  root=Path(tmp)
  with tarfile.open(archive,'r:gz') as tar:
   for member in tar.getmembers():
    path=Path(member.name)
    if path.is_absolute() or '..' in path.parts or not (member.isdir() or member.isfile()):raise ValueError('Invalid artifact path')
    target=root/path
    if member.isdir():target.mkdir(parents=True,exist_ok=True);continue
    target.parent.mkdir(parents=True,exist_ok=True)
    with tar.extractfile(member) as src,target.open('wb') as dst:shutil.copyfileobj(src,dst)
  jar=root/'backend.jar';thin=root/'backend-thin.jar';libraries=root/'libraries';libraries.mkdir();manifest=[]
  with zipfile.ZipFile(jar) as source,zipfile.ZipFile(thin,'w') as target:
   for entry in source.infolist():
    data=source.read(entry.filename)
    if entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):
     digest=hashlib.sha256(data).hexdigest();manifest.append({'name':entry.filename,'sha256':digest})
     if digest not in available:(libraries/(digest+'.jar')).write_bytes(data)
    else:target.writestr(entry,data)
  thin.replace(jar)
  (root/'dependency-manifest.json').write_text(json.dumps(manifest))
  files=sorted(p for p in root.rglob('*') if p.is_file() and p.name!='SHA256SUMS')
  (root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(root).as_posix()+'\n' for p in files))
  with tarfile.open(output,'w:gz') as tar:
   for name in ['backend.jar','web','migrations','licenses','libraries','dependency-manifest.json','release.json','SHA256SUMS']:tar.add(root/name,arcname=name)
  print('Publication archive:',Path(output).stat().st_size,'bytes; new libraries:',len(list(libraries.iterdir())))

if __name__=='__main__':prepare(sys.argv[1],sys.argv[2],set(json.load(open(sys.argv[3]))))
