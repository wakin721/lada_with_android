"""Export official YOLO v4-fast and build fixed synthetic phone-benchmark assets."""
from pathlib import Path
import os
os.environ['YOLO_CONFIG_DIR']='/var/tmp/lada-yolo-export-config'
os.environ['YOLO_AUTOINSTALL']='false'
os.environ['YOLO_OFFLINE']='true'
import hashlib,json,shutil,time,traceback
import numpy as np
import torch
import onnx
import onnxruntime as ort
from try_export import ROOT,sample_clips,compare


def main():
    output=ROOT/'artifacts/exports/detector'
    output.mkdir(parents=True,exist_ok=True)
    report_path=output/'export.json'
    report={'status':'started','model':'lada_mosaic_detection_model_v4_fast','input_shape':[1,3,640,640],
            'input_scope':'synthetic tensor, not user media or a detection accuracy evaluation'}
    try:
        checkpoint=ROOT/'artifacts/weights/lada_mosaic_detection_model_v4_fast.pt'
        expected='9a6b660d1d3e3797d39515e08b0e72fcc59815f38279faa7a4ab374ab2c1e3b4'
        assert hashlib.sha256(checkpoint.read_bytes()).hexdigest()==expected
        torch.set_num_threads(4)
        from ultralytics import YOLO,settings
        settings.update({'sync':False})
        from ultralytics.engine.exporter import Exporter
        model=YOLO(str(checkpoint))
        exporter=Exporter(overrides={'format':'onnx','imgsz':640,'batch':1,'dynamic':False,
                                    'simplify':False,'opset':19,'nms':False,'half':False,'device':'cpu'})
        exported=Path(exporter(model=model.model))
        target=output/'lada-yolo-v4-fast-640.onnx'
        shutil.copyfile(exported,target)
        onnx.checker.check_model(onnx.load(target),full_check=True)
        options=ort.SessionOptions(); options.intra_op_num_threads=4; options.inter_op_num_threads=1
        session=ort.InferenceSession(str(target),options,providers=['CPUExecutionProvider'])
        y,x=np.meshgrid(np.linspace(0,1,640,dtype=np.float32),np.linspace(0,1,640,dtype=np.float32),indexing='ij')
        clip=np.ascontiguousarray(np.stack((x,y,(x+y)/2))[None])
        with torch.inference_mode(): reference=exporter.model(torch.from_numpy(clip))
        if isinstance(reference,torch.Tensor): reference=(reference,)
        reference=[v.detach().numpy() for v in reference]
        actual=session.run(None,{session.get_inputs()[0].name:clip})
        checks=[compare(a,b,1e-4,1e-3) for a,b in zip(actual,reference)]
        if len(reference)!=len(actual) or not all(c['allclose'] for c in checks):
            raise AssertionError(f'YOLO ONNX mismatch: {checks}')
        report.update(status='verified',model_file=target.name,model_bytes=target.stat().st_size,
                      model_sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                      checkpoint_sha256=expected,task=model.task,input_name=session.get_inputs()[0].name,
                      output_shapes=[list(x.shape) for x in actual],validation=checks)
        assets=ROOT/'artifacts/benchmark-assets'; assets.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(target,assets/'detector.onnx')
        clip.astype('<f4').tofile(assets/'detector-input.bin')
        detector_refs=[]
        for i,ref in enumerate(reference):
            name=f'detector-reference-{i}.bin'; ref.astype('<f4').tofile(assets/name); detector_refs.append(name)
        rest=ROOT/'artifacts/exports/t3_256/lada-generic-v1.2-onnx-native.onnx'
        shutil.copyfile(rest,assets/'restorer.onnx')
        sample_clips(3,256)['block_noise'].astype('<f4').tofile(assets/'restorer-input.bin')
        with np.load(ROOT/'artifacts/exports/t3_256/reference.npz') as z:
            z['block_noise'].astype('<f4').tofile(assets/'restorer-reference-0.bin')
        specs=[{'id':'YOLO v4-fast 640','model':'detector.onnx','input_file':'detector-input.bin',
                'input_name':report['input_name'],'shape':[1,3,640,640],'frames_per_call':1,
                'sha256':report['model_sha256'],'reference_outputs':detector_refs,'warmups':5,'iterations':20},
               {'id':'BasicVSR++ generic v1.2 T3 256','model':'restorer.onnx','input_file':'restorer-input.bin',
                'input_name':'frames','shape':[1,3,3,256,256],'frames_per_call':3,
                'sha256':hashlib.sha256(rest.read_bytes()).hexdigest(),
                'reference_outputs':['restorer-reference-0.bin'],'warmups':2,'iterations':5}]
        (assets/'models.json').write_text(json.dumps({'models':specs},indent=2),encoding='utf-8')
        print(json.dumps(report,indent=2),flush=True)
    except BaseException as error:
        report.update(status='failed',error_type=type(error).__name__,error=str(error),traceback=traceback.format_exc())
        traceback.print_exc()
        raise
    finally: report_path.write_text(json.dumps(report,indent=2),encoding='utf-8')

if __name__=='__main__': main()
