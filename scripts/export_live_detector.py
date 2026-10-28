from pathlib import Path
import os
os.environ['YOLO_CONFIG_DIR']='/var/tmp/lada-yolo-export-config'
os.environ['YOLO_AUTOINSTALL']='false'
os.environ['YOLO_OFFLINE']='true'
Path(os.environ['YOLO_CONFIG_DIR']).mkdir(parents=True,exist_ok=True)
import hashlib,json,shutil
import torch,numpy as np,onnx,onnxruntime as ort
from try_export import ROOT,compare
from ultralytics import YOLO,settings
from ultralytics.engine.exporter import Exporter
settings.update({'sync':False})
torch.set_num_threads(4)
model=YOLO(str(ROOT/'artifacts/weights/lada_mosaic_detection_model_v4_fast.pt'))
exporter=Exporter(overrides={'format':'onnx','imgsz':320,'batch':1,'dynamic':False,'simplify':False,'opset':19,'nms':False,'half':False,'device':'cpu'})
source=Path(exporter(model=model.model))
target=ROOT/'artifacts/exports/detector/lada-yolo-v4-fast-320.onnx'
shutil.copyfile(source,target)
onnx.checker.check_model(onnx.load(target),full_check=True)
inputs=np.random.default_rng(721).random((1,3,320,320),dtype=np.float32)
with torch.inference_mode(): ref=exporter.model(torch.from_numpy(inputs))
opts=ort.SessionOptions();opts.intra_op_num_threads=4
session=ort.InferenceSession(str(target),opts,providers=['CPUExecutionProvider'])
actual=session.run(None,{'images':inputs})
checks=[compare(a,b.detach().numpy(),1e-4,1e-3) for a,b in zip(actual,ref)]
assert all(x['allclose'] for x in checks),checks
report={'status':'verified','shape':[1,3,320,320],'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'validation':checks}
(target.parent/'live-export.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2),flush=True)
