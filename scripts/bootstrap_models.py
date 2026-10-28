"""Get the exact upstream source and official weights used by the export probes."""
from pathlib import Path
import hashlib,subprocess,urllib.request
ROOT=Path(__file__).resolve().parents[1]
REVISION='20cb34a20a83c72c87a991d2c949032c70085b16'
source=ROOT/'.tools/lada'
source.parent.mkdir(parents=True,exist_ok=True)
if not (source/'.git').exists():
    subprocess.run(['git','clone','--no-checkout','https://github.com/ladaapp/lada.git',str(source)],check=True)
    subprocess.run(['git','-C',str(source),'checkout','--detach',REVISION],check=True)
actual=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
if actual!=REVISION: raise RuntimeError('Upstream checkout is a different revision; use a fresh tooling folder rather than resetting local edits')
weights=ROOT/'artifacts/weights';weights.mkdir(parents=True,exist_ok=True)
for name,checksum in {
    'lada_mosaic_restoration_model_generic_v1.2.pth':'d404152576ce64fb5b2f315c03062709dac4f5f8548934866cd01c823c8104ee',
    'lada_mosaic_detection_model_v4_fast.pt':'9a6b660d1d3e3797d39515e08b0e72fcc59815f38279faa7a4ab374ab2c1e3b4'
}.items():
    target=weights/name
    if not target.exists():urllib.request.urlretrieve('https://huggingface.co/ladaapp/lada/resolve/main/'+name+'?download=true',target)
    actual=hashlib.sha256(target.read_bytes()).hexdigest()
    if actual!=checksum:raise RuntimeError('Checksum mismatch: '+name)
    print(name,'SHA256 verified',flush=True)
