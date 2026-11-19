"""Publish already signed bytes. No app source, credentials or signing key is required here."""
import base64, hashlib, json, os, re, subprocess, tempfile, zipfile
from pathlib import Path

REPO='bladimirreparacionescelulares-afk/prestamax-actualizaciones'
CERT='dbbe6a521eb39f1234f12811810d5775d8fed45b5160840ec58cf7e719551414'
tag=os.environ['RELEASE_TAG'];assert re.fullmatch(r'v[0-9]{1,3}(\.[0-9]{1,3}){1,2}',tag)
assert os.environ['GH_REPO']==REPO

def cmd(*args):return subprocess.check_output(args,text=True).strip()
def api(path):return json.loads(cmd('gh','api',path))
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def decode(s):return base64.urlsafe_b64decode(s+'='*((-len(s))%4))

apk=Path('PrestaMax.apk');manifest=Path('actualizacion.json');sums=Path('SHA256SUMS.txt')
assert 0<apk.stat().st_size<=150*1024*1024 and manifest.stat().st_size<=65536
subprocess.run(['sha256sum','--check',str(sums)],check=True)
envelope=json.loads(manifest.read_text());payload=envelope['payload'];signature=envelope['signature']
assert re.fullmatch(r'[A-Za-z0-9_-]+',payload) and re.fullmatch(r'[A-Za-z0-9_-]+',signature)
r=json.loads(decode(payload));assert r['protocol']==1 and r['packageName']=='com.misprestamos.app'
assert tag=='v'+r['versionName'] and isinstance(r['versionCode'],int) and 0<r['versionCode']<=2100000000
assert r['apkUrl']==f'https://github.com/{REPO}/releases/download/{tag}/PrestaMax.apk'
assert r['bytes']==apk.stat().st_size and r['sha256']==digest(apk)
assert isinstance(r['notes'],list) and len(r['notes'])<=8 and all(isinstance(n,str) and 0<len(n)<=240 for n in r['notes'])
sdk=Path(os.environ['ANDROID_HOME']);tools=sorted((sdk/'build-tools').glob('*'),reverse=True)
apksigner=next(t/'apksigner' for t in tools if (t/'apksigner').is_file());aapt=next(t/'aapt' for t in tools if (t/'aapt').is_file())
certs=cmd(str(apksigner),'verify','--print-certs','--print-certs-pem',str(apk));assert re.findall(r'certificate SHA-256 digest: ([0-9a-f]+)',certs)==[CERT]
badging=cmd(str(aapt),'dump','badging',str(apk));assert 'application-debuggable' not in badging
assert f"name='com.misprestamos.app' versionCode='{r['versionCode']}' versionName='{r['versionName']}'" in badging
assert f"sdkVersion:'{r['minSdk']}'" in badging
with tempfile.TemporaryDirectory() as temp:
    temp=Path(temp)
    pem=re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----',certs,re.S);assert len(pem)==1
    (temp/'cert.pem').write_text(pem[0]+'\n')
    public=cmd('openssl','x509','-in',str(temp/'cert.pem'),'-pubkey','-noout');(temp/'key.pem').write_text(public+'\n')
    (temp/'message').write_bytes(('PMX-UPDATE-V1.'+payload).encode());(temp/'signature').write_bytes(decode(signature))
    subprocess.run(['openssl','dgst','-sha256','-verify',str(temp/'key.pem'),'-signature',str(temp/'signature'),str(temp/'message')],check=True)

head=cmd('git','rev-parse','HEAD')
files=[apk,manifest,sums]
release_result=subprocess.run(['gh','release','view',tag,'--json','apiUrl,isDraft,targetCommitish'],capture_output=True,text=True)
if release_result.returncode:
    notes=Path('notas-release.md');notes.write_text(f"PrestaMax {r['versionName']} · código {r['versionCode']}\n\n"+'\n'.join('- '+n for n in r['notes'])+'\n\nInstala como actualización, sin desinstalar. Se requiere una licencia autorizada.\n')
    subprocess.run(['gh','release','create',tag,'--verify-tag','--draft','--target',head,'--title',f"PrestaMax {r['versionName']}",'--notes-file',str(notes)],check=True)
view=json.loads(cmd('gh','release','view',tag,'--json','apiUrl,isDraft,targetCommitish'))
assert view['targetCommitish']==head
release=api(view['apiUrl'])
if release['draft']:
    existing={a['name'] for a in release['assets']}
    assert existing<={f.name for f in files}
    for f in files:
        if f.name not in existing:subprocess.run(['gh','release','upload',tag,str(f)],check=True)
    release=api(view['apiUrl'])
assets={a['name']:a for a in release['assets']};assert set(assets)=={f.name for f in files}
for f in files:
    a=assets[f.name];assert a['state']=='uploaded' and a['size']==f.stat().st_size and a['digest']=='sha256:'+digest(f)
if release['draft']:
    # An older tag may be retained as history, but may not replace a newer update feed.
    latest=subprocess.run(['gh','release','view','--json','tagName'],capture_output=True,text=True)
    if latest.returncode==0:
        old=json.loads(latest.stdout)['tagName']
        if old!=tag:
            old_blob=cmd('git','show',old+':actualizacion.json');old_r=json.loads(decode(json.loads(old_blob)['payload']))
            assert old_r['versionCode']<r['versionCode'],'No convertir una entrega anterior en latest'
    subprocess.run(['gh','release','edit',tag,'--draft=false','--latest'],check=True)
print('Publicación verificada: paquete, certificado, firma del anuncio, hashes, tamaños y tres adjuntos exactos.')
