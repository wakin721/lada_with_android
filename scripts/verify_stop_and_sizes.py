"""Runtime regression checks for cancellation and both prototype input sizes (host CPU)."""
from pathlib import Path
import json,time,threading
import numpy as np
import onnxruntime as ort
ROOT=Path(__file__).resolve().parents[1]
options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
report={'scope':'host Linux ARM64 CPU ONNX Runtime, not a new phone/GPU benchmark','runtime':ort.__version__}
light=ort.InferenceSession(str(ROOT/'artifacts/device/assets/mobile_deblock_fp32.onnx'),options,providers=['CPUExecutionProvider'])
sizes=[]
for size in (128,256):
    data=np.random.default_rng(size).random((1,3,size,size),dtype=np.float32)
    result=light.run(None,{'image':data})[0]
    assert result.shape==data.shape and np.isfinite(result).all()
    sizes.append({'size':size,'input_shape':list(data.shape),'output_shape':list(result.shape),'finite':True})
report['light_sizes']=sizes
session=ort.InferenceSession(str(ROOT/'artifacts/exports/t3_256/lada-generic-v1.2-onnx-native.onnx'),options,providers=['CPUExecutionProvider'])
data=np.zeros((1,3,3,256,256),dtype=np.float32)
run_options=ort.RunOptions();started=threading.Event();outcome={}
def run():
    started.set()
    try:
        session.run(None,{'frames':data},run_options)
        outcome['completed_without_termination']=True
    except Exception as error:
        outcome['error_type']=type(error).__name__;outcome['message']=str(error)
thread=threading.Thread(target=run,daemon=True);thread.start();assert started.wait(5)
time.sleep(.05)
then=time.perf_counter();run_options.terminate=True;thread.join(5)
report['cancellation']={'returned':not thread.is_alive(),'after_signal_ms':(time.perf_counter()-then)*1000,**outcome}
assert not thread.is_alive(),'Inference did not return after terminate signal'
assert 'terminate' in outcome.get('message','').lower(),outcome
result=session.run(None,{'frames':data})[0]
assert result.shape==data.shape and np.isfinite(result).all()
report['new_run_after_termination']={'shape':list(result.shape),'finite':True}
p=ROOT/'artifacts/app/stop-and-sizes-runtime.json';p.write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2),flush=True)
