"""Package verified models and generate a small non-personal demo video."""
from pathlib import Path
import hashlib,json,shutil
ROOT=Path(__file__).resolve().parents[1]
def main():
    assets=ROOT/'artifacts/app-assets';assets.mkdir(parents=True,exist_ok=True)
    sources={
        'detector.onnx':ROOT/'artifacts/exports/detector/lada-yolo-v4-fast-640.onnx',
        'detector-live.onnx':ROOT/'artifacts/exports/detector/lada-yolo-v4-fast-320.onnx',
        'restorer.onnx':ROOT/'artifacts/exports/t3_256/lada-generic-v1.2-onnx-native.onnx',
        'restorer-portable.onnx':ROOT/'artifacts/exports/t3_256/lada-generic-v1.2-onnx-portable.onnx'
    }
    for report in ['artifacts/exports/detector/export.json','artifacts/exports/detector/live-export.json','artifacts/exports/t3_256/onnx-native.json','artifacts/exports/t3_256/onnx-portable.json']:
        assert json.loads((ROOT/report).read_text())['status']=='verified',report
    prototype=ROOT/'artifacts/device/assets/mobile_deblock_fp32.onnx'
    if prototype.is_file():
        assert hashlib.sha256(prototype.read_bytes()).hexdigest()=='40d138da4c47093fa19eeb9932b507838f2f8404fcfcb4ff462814557b588490'
        sources['mobile-deblock.onnx']=prototype
    manifest={}
    for name,source in sources.items():
        shutil.copyfile(source,assets/name)
        manifest[name]={'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'bytes':source.stat().st_size}
    (assets/'model-info.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    shutil.copyfile(ROOT/'.tools/lada/LICENSE.md',assets/'LADA-LICENSE.md')
    import cv2
    import numpy as np
    path=assets/'demo.mp4'
    writer=cv2.VideoWriter(str(path),cv2.VideoWriter_fourcc(*'mp4v'),24,(720,480))
    assert writer.isOpened(),'Could not create demonstration video'
    xx,yy=np.meshgrid(np.arange(720),np.arange(480))
    background=np.stack((80+xx*.10,160-yy*.12,80+yy*.18),axis=-1).clip(0,255).astype(np.uint8)
    for i in range(144):
        frame=background.copy()
        cv2.rectangle(frame,(90,90),(630,390),(210,224,235),-1)
        for col in range(8):
            color=(int(70+col*17),int(180-col*8),int(170+col*7))
            cv2.rectangle(frame,(140+col*55,140),(180+col*55,330),color,-1)
        x=260+int(70*np.sin(i/28));y=225+int(35*np.cos(i/32))
        cv2.circle(frame,(x,y),66,(52,72,196),-1)
        cv2.line(frame,(x-35,y),(x+35,y),(240,240,250),8)
        cv2.line(frame,(x,y-35),(x,y+35),(240,240,250),8)
        roi=frame[130:350,230:450]
        low=cv2.resize(roi,(14,14),interpolation=cv2.INTER_AREA)
        frame[130:350,230:450]=cv2.resize(low,(220,220),interpolation=cv2.INTER_NEAREST)
        cv2.putText(frame,'LADA / LOCAL PREVIEW',(26,42),cv2.FONT_HERSHEY_SIMPLEX,.8,(245,245,250),2,cv2.LINE_AA)
        cv2.putText(frame,'Synthetic demo - choose your own video',(90,438),cv2.FONT_HERSHEY_SIMPLEX,.65,(245,245,250),1,cv2.LINE_AA)
        writer.write(frame)
    writer.release()
    assert path.stat().st_size>1000
    print('Prepared app assets',json.dumps(manifest), 'demo_bytes',path.stat().st_size,flush=True)
if __name__=='__main__':main()
