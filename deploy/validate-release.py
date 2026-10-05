#!/usr/bin/env python3
"""Validate an untrusted release archive before extracting it into an isolated staging directory."""
from pathlib import Path, PurePosixPath
import hashlib,json,sys,tarfile,shutil

def extract(archive: Path, target: Path, commit: str):
    with tarfile.open(archive, 'r:gz') as tar:
        members=tar.getmembers()
        if len(members)>10000 or sum(m.size for m in members)>350*1024*1024:
            raise ValueError('Release exceeds permitted size')
        seen=set()
        for member in members:
            path=PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or member.name in seen:
                raise ValueError('Invalid or duplicate archive path')
            if not (member.isdir() or member.isfile()):
                raise ValueError('Archive links and special files are forbidden')
            if not (str(path) in {'backend.jar','release.json','SHA256SUMS','web','migrations'} or str(path).startswith(('web/','migrations/'))):
                raise ValueError('Unexpected release member')
            seen.add(member.name)
        target.mkdir(parents=True,exist_ok=True)
        for member in members:
            output=target/member.name
            if member.isdir():output.mkdir(parents=True,exist_ok=True);output.chmod(0o755);continue
            output.parent.mkdir(parents=True,exist_ok=True)
            with tar.extractfile(member) as src,output.open('wb') as dst:shutil.copyfileobj(src,dst)
            output.chmod(0o644)
    metadata=json.loads((target/'release.json').read_text())
    if metadata['commit']!=commit:raise ValueError('Release commit mismatch')
    hashed=set()
    for line in (target/'SHA256SUMS').read_text().splitlines():
        digest,name=line.split('  ',1)
        file=target/name
        if name not in seen or not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=digest:
            raise ValueError('Release checksum mismatch')
        hashed.add(name)
    files={m.name for m in members if m.isfile()}-{'SHA256SUMS'}
    if files!=hashed:raise ValueError('Unverified release files')
    for required in ['backend.jar','web/index.html','web/order/index.html']:
        if required not in hashed:raise ValueError('Incomplete release')

if __name__=='__main__':
    extract(Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3])
