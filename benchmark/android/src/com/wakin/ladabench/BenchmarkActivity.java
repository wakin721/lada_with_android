package com.wakin.ladabench;

import ai.onnxruntime.*;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class BenchmarkActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView text;
    private Button button;
    private volatile boolean cancelled;
    private String backend = "cpu";
    private final StringBuilder progress = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String requested = getIntent().getStringExtra("backend");
        if ("webgpu".equals(requested)) backend = "webgpu";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(32, 64, 32, 32);
        button = new Button(this);
        button.setText("开始 ONNX " + backend.toUpperCase(Locale.ROOT) + " 测速");
        column.addView(button);
        text = new TextView(this);
        text.setTextSize(15);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
        log("Lada ONNX 测速\n后端：" + backend + "；独立测试检测与修复，不读取相册。\n");
        button.setOnClickListener(v -> start());
        if (getIntent().getBooleanExtra("autorun", false)) start();
    }

    private void log(String message) {
        synchronized (progress) { progress.append(message).append('\n'); }
        runOnUiThread(() -> { synchronized (progress) { text.setText(progress.toString()); } });
    }

    private void start() {
        button.setEnabled(false);
        worker.execute(() -> {
            JSONObject report = new JSONObject();
            try {
                report.put("status", "running");
                report.put("device_model", Build.MODEL);
                report.put("android_sdk", Build.VERSION.SDK_INT);
                report.put("started_at_epoch_ms", System.currentTimeMillis());
                report.put("backend", backend.equals("cpu") ? "CPUExecutionProvider; no accelerator provider registered" : "WebGPUExecutionProvider with CPU fallback; see per-model profile assignment");
                report.put("profiling_enabled", backend.equals("webgpu"));
                report.put("threads", 4);
                report.put("timing_scope", "total_ms: tensor creation + session.run + all output copies + tensor/result close; run_ms: session.run only; excludes model load, asset IO, correctness comparison, video decode, preprocessing, NMS, masks, tracking, blending, encoding and UI");
                report.put("input_scope", "fixed synthetic inputs; model-only benchmark, not end-to-end restoration quality or video FPS");
                report.put("device_before", deviceState());
                OrtEnvironment env = OrtEnvironment.getEnvironment();
                env.setTelemetry(false);
                report.put("runtime", env.getVersion());
                JSONArray available = new JSONArray();
                for (OrtProvider provider : OrtEnvironment.getAvailableProviders()) available.put(provider.name());
                report.put("compiled_available_providers", available);
                JSONObject manifest = new JSONObject(new String(readAsset("models.json"), "UTF-8"));
                JSONArray models = manifest.getJSONArray("models");
                JSONArray results = new JSONArray();
                report.put("models", results);
                save(report);
                boolean allPassed = true;
                for (int i = 0; i < models.length(); i++) {
                    if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
                    JSONObject spec = models.getJSONObject(i);
                    if (spec.has("only_backend") && !backend.equals(spec.getString("only_backend"))) continue;
                    try {
                        JSONObject result = benchmark(env, spec);
                        results.put(result);
                        if (!"verified".equals(result.optString("status"))) allPassed = false;
                    } catch (Exception error) {
                        JSONObject result = new JSONObject();
                        result.put("id", spec.getString("id"));
                        result.put("status", "failed");
                        result.put("error", error.toString());
                        results.put(result);
                        allPassed = false;
                        log(spec.getString("id") + " 失败：" + error);
                    }
                    save(report);
                }
                report.put("status", "completed");
                report.put("all_models_verified", allPassed);
                report.put("device_after", deviceState());
                report.put("finished_at_epoch_ms", System.currentTimeMillis());
                save(report);
                log("完成。详细数据已保存在应用私有目录。");
            } catch (Exception error) {
                try { report.put("status", "failed"); report.put("error", error.toString()); save(report); }
                catch (Exception ignored) { }
                log("测试中止：" + error);
            } finally { runOnUiThread(() -> button.setEnabled(true)); }
        });
    }

    private JSONObject benchmark(OrtEnvironment env, JSONObject spec) throws Exception {
        String id = spec.getString("id");
        log("加载 " + id);
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("spec", spec);
        out.put("device_before", deviceState());
        String assetName = spec.getString("model");
        File file = new File(getFilesDir(), assetName);
        // Always copy this benchmark APK's model; a previous run cannot substitute one.
        try (InputStream in = getAssets().open(assetName); OutputStream stream = new FileOutputStream(file)) {
            byte[] block = new byte[1 << 20]; int n;
            while ((n = in.read(block)) >= 0) stream.write(block, 0, n);
        }
        String digest = sha256(file);
        if (!digest.equals(spec.getString("sha256"))) throw new IOException("Model SHA256 mismatch");
        out.put("model_sha256", digest);
        float[] input = floats(readAsset(spec.getString("input_file")));
        JSONArray dims = spec.getJSONArray("shape");
        long[] shape = new long[dims.length()]; long elements = 1;
        for (int i=0;i<shape.length;i++) { shape[i]=dims.getLong(i); elements*=shape[i]; }
        if (elements != input.length) throw new IOException("Input size mismatch");
        JSONArray referenceNames = spec.getJSONArray("reference_outputs");
        float[][] references = new float[referenceNames.length()][];
        float[][] scratch = new float[referenceNames.length()][];
        for (int i=0;i<references.length;i++) {
            references[i] = floats(readAsset(referenceNames.getString(i)));
            scratch[i] = new float[references[i].length];
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            if (backend.equals("webgpu")) {
                Map<String,String> gpuOptions = new HashMap<>();
                gpuOptions.put("preferredLayout", "NHWC");
                options.addWebGPU(gpuOptions);
                options.enableProfiling(new File(getFilesDir(),"webgpu-profile-" + assetName.replace(".onnx","")).getAbsolutePath());
            }
            long loadStart = System.nanoTime();
            try (OrtSession session = env.createSession(file.getAbsolutePath(), options)) {
                out.put("session_load_ms", (System.nanoTime()-loadStart)/1e6);
                if (session.getNumInputs()!=1 || session.getNumOutputs()!=references.length)
                    throw new IOException("Unexpected model input/output count");
                String inputName = session.getInputNames().iterator().next();
                if (!inputName.equals(spec.getString("input_name"))) throw new IOException("Input name mismatch");
                int warmups = spec.getInt("warmups"); int iterations = spec.getInt("iterations");
                for (int i=0;i<warmups;i++) {
                    if (cancelled) throw new InterruptedException("cancelled");
                    invoke(env,session,inputName,input,shape,scratch);
                    log(id + " 预热 " + (i+1) + "/" + warmups);
                }
                invoke(env,session,inputName,input,shape,scratch);
                JSONArray checks = new JSONArray(); boolean correct = true;
                for (int o=0;o<references.length;o++) {
                    double max=0, sum=0, squares=0; int mismatches=0, nonFinite=0;
                    for (int i=0;i<scratch[o].length;i++) {
                        double value=scratch[o][i], expected=references[o][i];
                        if (!Double.isFinite(value)) { nonFinite++; continue; }
                        double d=Math.abs(value-expected);
                        max=Math.max(max,d); sum+=d; squares+=d*d;
                        if (d>1e-4+1e-3*Math.abs(expected)) mismatches++;
                    }
                    JSONObject check=new JSONObject();
                    check.put("output_index",o); check.put("elements",scratch[o].length);
                    check.put("max_abs_error",max); check.put("mean_abs_error",sum/scratch[o].length);
                    check.put("rmse",Math.sqrt(squares/scratch[o].length));
                    check.put("mismatches",mismatches); check.put("non_finite",nonFinite);
                    check.put("atol",1e-4); check.put("rtol",1e-3);
                    checks.put(check); correct &= mismatches==0 && nonFinite==0;
                }
                out.put("correctness", checks);
                if (!correct) {
                    out.put("status","numerical_mismatch");
                    log(id + " 输出与电脑参考结果不一致，停止该模型测速。");
                    return out;
                }
                double[] total=new double[iterations], runs=new double[iterations];
                for (int i=0;i<iterations;i++) {
                    if (cancelled) throw new InterruptedException("cancelled");
                    double[] sample=invoke(env,session,inputName,input,shape,scratch);
                    total[i]=sample[0]; runs[i]=sample[1];
                    log(String.format(Locale.ROOT,"%s %d/%d：%.2f ms",id,i+1,iterations,total[i]));
                }
                out.put("total_ms",statistics(total)); out.put("session_run_ms",statistics(runs));
                out.put("amortized_total_median_ms_per_frame",median(total)/spec.getInt("frames_per_call"));
                out.put("status","verified");
                if (backend.equals("webgpu")) {
                    String profilePath=session.endProfiling();
                    JSONArray events=new JSONArray(new String(readFile(new File(profilePath)),"UTF-8"));
                    Map<String,Set<String>> assignments=new TreeMap<>();
                    Map<String,Integer> counts=new TreeMap<>();
                    for (int i=0;i<events.length();i++) {
                        JSONObject event=events.getJSONObject(i);
                        JSONObject data=event.optJSONObject("args");
                        if (data==null || !data.has("provider")) continue;
                        String provider=data.getString("provider");
                        assignments.computeIfAbsent(provider,k->new HashSet<>()).add(data.optString("node_index",event.optString("name")));
                        counts.put(provider,counts.getOrDefault(provider,0)+1);
                    }
                    JSONObject distribution=new JSONObject();
                    boolean anyGpu=false;
                    for (String provider:assignments.keySet()) {
                        JSONObject d=new JSONObject(); d.put("unique_executed_nodes",assignments.get(provider).size());
                        d.put("kernel_events",counts.get(provider)); distribution.put(provider,d);
                        if (provider.toLowerCase(Locale.ROOT).contains("webgpu")) anyGpu=true;
                    }
                    out.put("profile_node_assignment",distribution);
                    out.put("profile_file",new File(profilePath).getName());
                    out.put("gpu_nodes_verified",anyGpu);
                    if (!anyGpu) out.put("status","no_gpu_nodes");
                }
            }
        }
        out.put("device_after", deviceState());
        return out;
    }

    private double[] invoke(OrtEnvironment env, OrtSession session, String name, float[] input,
                            long[] shape, float[][] outputs) throws Exception {
        long start=System.nanoTime(); long runElapsed;
        try (OnnxTensor tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),shape)) {
            long runStart=System.nanoTime();
            try (OrtSession.Result result=session.run(Collections.singletonMap(name,tensor))) {
                runElapsed=System.nanoTime()-runStart;
                for (int i=0;i<outputs.length;i++) {
                    FloatBuffer data=((OnnxTensor)result.get(i)).getFloatBuffer();
                    if (data.remaining()!=outputs[i].length) throw new IOException("Output size mismatch");
                    data.get(outputs[i]);
                }
            }
        }
        return new double[]{(System.nanoTime()-start)/1e6, runElapsed/1e6};
    }

    private JSONObject deviceState() throws Exception {
        JSONObject state=new JSONObject();
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        state.put("thermal_status",Build.VERSION.SDK_INT>=29?power.getCurrentThermalStatus():-1);
        Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery!=null) {
            state.put("battery_temperature_c",battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)/10.0);
            state.put("battery_level",battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1));
            state.put("plugged",battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1));
        }
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        state.put("available_memory_mib",memory.availMem/1048576.0);
        state.put("process_pss_mib",Debug.getPss()/1024.0);
        state.put("native_heap_allocated_mib",Debug.getNativeHeapAllocatedSize()/1048576.0);
        state.put("memory_scope","point samples before/after; not peak memory");
        return state;
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[1<<20]; int n; while ((n=in.read(b))>=0) out.write(b,0,n); return out.toByteArray();
        }
    }
    private byte[] readAsset(String name) throws IOException {
        try (InputStream in=getAssets().open(name); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[1<<20]; int n;
            while ((n=in.read(b))>=0) out.write(b,0,n);
            return out.toByteArray();
        }
    }
    private static float[] floats(byte[] bytes) throws IOException {
        if (bytes.length%4!=0) throw new IOException("Bad float buffer");
        float[] out=new float[bytes.length/4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (InputStream in=new FileInputStream(file)) { byte[] b=new byte[1<<20]; int n;
            while ((n=in.read(b))>=0) digest.update(b,0,n); }
        StringBuilder s=new StringBuilder();
        for (byte b:digest.digest()) s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private static double median(double[] values) { double[] a=values.clone(); Arrays.sort(a); int n=a.length;
        return n%2==1?a[n/2]:(a[n/2-1]+a[n/2])/2; }
    private static JSONObject statistics(double[] values) throws Exception {
        double[] a=values.clone(); Arrays.sort(a); double sum=0;
        for (double x:a) sum+=x;
        JSONObject out=new JSONObject();
        out.put("median",median(a)); out.put("mean",sum/a.length); out.put("min",a[0]); out.put("max",a[a.length-1]);
        out.put("p90",a[(int)Math.ceil(.9*a.length)-1]);
        JSONArray samples=new JSONArray(); for (double x:values) samples.put(x);
        out.put("samples",samples); return out;
    }
    private void save(JSONObject report) throws IOException {
        try (Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(),backend.equals("cpu")?"benchmark-results.json":"benchmark-results-webgpu.json")),"UTF-8")) {
            try { writer.write(report.toString(2)); } catch(JSONException e) { throw new IOException(e); }
        }
    }
    @Override public void onDestroy() { cancelled=true; worker.shutdownNow(); super.onDestroy(); }
}
