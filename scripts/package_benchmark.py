from pathlib import Path
import zipfile
root=Path(__file__).resolve().parents[1]
build=root/'artifacts/benchmark-build'
with zipfile.ZipFile(build/'base.apk') as base, zipfile.ZipFile(build/'unsigned.apk','w') as out:
    for i in base.infolist(): out.writestr(i,base.read(i.filename))
    out.write(build/'dex/classes.dex','classes.dex',compress_type=zipfile.ZIP_DEFLATED)
    for p in (root/'.tools/ort-android/jni/arm64-v8a').glob('*.so'):
        out.write(p,'lib/arm64-v8a/'+p.name,compress_type=zipfile.ZIP_DEFLATED)
    for p in (root/'artifacts/benchmark-assets').iterdir():
        if p.is_file(): out.write(p,'assets/'+p.name,compress_type=zipfile.ZIP_DEFLATED)
print('Packed benchmark APK')
