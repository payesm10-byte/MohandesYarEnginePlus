package ir.mohandesyar.engineplus;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Environment;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import android.widget.Toast;
import org.json.JSONObject;
import com.google.firebase.auth.FirebaseAuth;
public class MainActivity extends Activity {
    private WebView webView;
    private FirebaseAuth firebaseAuth;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER_REQUEST = 701;
    private Uri pendingCameraUri;
    private static final int LOCATION_PERMISSION_REQUEST = 702;
    private static final int CAMERA_PERMISSION_REQUEST = 703;
    private PermissionRequest pendingCameraRequest;
    private String pendingCallback = "onNativeLocationResult";
    private LocationListener pendingListener;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        firebaseAuth = FirebaseAuth.getInstance();
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean videoRequested = false;
                    for (String resource : request.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) videoRequested = true;
                    if (!videoRequested) { request.deny(); return; }
                    if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingCameraRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingCameraRequest == request) pendingCameraRequest = null;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                pendingCameraUri = null;
                Intent picker = params.createIntent();
                try {
                    String[] types = params.getAcceptTypes();
                    boolean wantsImage = false;
                    if (types != null) for (String type : types) if (type != null && (type.contains("image") || type.equals("*/*"))) wantsImage = true;
                    if (wantsImage && params.isCaptureEnabled()) {
                        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        if (camera.resolveActivity(getPackageManager()) != null) {
                            File dir = new File(getCacheDir(), "camera");
                            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create camera cache");
                            File photo = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
                            pendingCameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", photo);
                            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
                            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            Intent chooser = Intent.createChooser(picker, "انتخاب تصویر از دوربین یا گالری");
                            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                            startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                        } else startActivityForResult(picker, FILE_CHOOSER_REQUEST);
                    } else {
                        startActivityForResult(picker, FILE_CHOOSER_REQUEST);
                    }
                } catch (Exception e) {
                    if (fileCallback != null) fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    pendingCameraUri = null;
                    Toast.makeText(MainActivity.this, "باز کردن دوربین یا انتخاب فایل ممکن نشد.", Toast.LENGTH_LONG).show();
                    return true;
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new GeoBridge(), "AndroidGeo");
        webView.addJavascriptInterface(new ShareBridge(), "AndroidShare");
        webView.addJavascriptInterface(new NativeStorageBridge(), "AndroidStorage");
        webView.addJavascriptInterface(new FirebaseAuthBridge(), "AndroidAuth");
        webView.loadUrl("file:///android_asset/index.html");
    }

private class FirebaseAuthBridge {

    @JavascriptInterface
    public void login(String email, String password, String callbackName) {
        if (email == null || password == null ||
            callbackName == null ||
            !callbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,60}")) {
            sendAuthResult(callbackName, false, "اطلاعات ورود نامعتبر است.");
            return;
        }

        firebaseAuth.signInWithEmailAndPassword(
            email.trim(), password
        ).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                sendAuthResult(callbackName, true, "ورود موفق بود.");
            } else {
                sendAuthResult(
                    callbackName,
                    false,
                   "خطای Firebase: " +
(task.getException() != null
    ? task.getException().getMessage()
    : "خطای نامشخص")
                );
            }
        });
    }

    private void sendAuthResult(
        String callbackName, boolean success, String message
    ) {
        if (callbackName == null ||
            !callbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,60}")) {
            return;
        }

        try {
            JSONObject result = new JSONObject();
            result.put("success", success);
            result.put("message", message);

            String script =
                "if(typeof window[" + JSONObject.quote(callbackName) +
                "] === 'function') window[" +
                JSONObject.quote(callbackName) + "](" +
                JSONObject.quote(result.toString()) + ");";

            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(script, null);
                }
            });
        } catch (Exception ignored) {
        }
    }
}

    private class NativeStorageBridge {
        private File stateFile() { return new File(getFilesDir(), "mohandesyar_state.json"); }

        @JavascriptInterface public String loadState() {
            File file = stateFile();
            if (!file.exists()) return "";
            try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                return out.toString("UTF-8");
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public boolean saveState(String json) {
            if (json == null || json.length() > 40 * 1024 * 1024) return false;
            File target = stateFile();
            File temp = new File(getFilesDir(), "mohandesyar_state.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
                if (target.exists() && !target.delete()) return false;
                return temp.renameTo(target);
            } catch (Exception e) {
                if (temp.exists()) temp.delete();
                return false;
            }
        }
    }

    private class ShareBridge {
        @JavascriptInterface public void shareText(String title, String text) {
            runOnUiThread(() -> {
                if (text == null || text.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "متنی برای اشتراک‌گذاری وجود ندارد.", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, title == null ? "گزارش مهندس‌یار" : title);
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "اشتراک‌گذاری گزارش مهندس‌یار"));
            });
        }
    }

    private class GeoBridge {
        @JavascriptInterface public void requestLocation(String callbackName) {
            runOnUiThread(() -> {
                pendingCallback = (callbackName != null && callbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,60}")) ? callbackName : "onNativeLocationResult";
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
                } else {
                    findLocation();
                }
            });
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            boolean granted = false;
            for (int result : grantResults) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted) findLocation(); else sendLocationError("اجازه دسترسی به موقعیت داده نشد");
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingCameraRequest != null) {
                if (granted) pendingCameraRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                else pendingCameraRequest.deny();
                pendingCameraRequest = null;
            }
        }
    }

    private void findLocation() {
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (manager.isProviderEnabled(provider)) {
                        Location last = manager.getLastKnownLocation(provider);
                        if (last != null && (best == null || last.getTime() > best.getTime())) best = last;
                    }
                } catch (SecurityException ignored) { }
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 120000) { sendLocation(best); return; }
            String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            pendingListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) { try { manager.removeUpdates(this); } catch (Exception ignored) { } sendLocation(location); }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            };
            if (Build.VERSION.SDK_INT >= 30) manager.getCurrentLocation(provider, null, getMainExecutor(), location -> {
                if (location != null) { pendingListener = null; sendLocation(location); } else { pendingListener = null; sendLocationError("موقعیت فعلی پیدا نشد"); }
            });
            else manager.requestSingleUpdate(provider, pendingListener, Looper.getMainLooper());
            webView.postDelayed(() -> { if (pendingListener != null) { try { manager.removeUpdates(pendingListener); } catch (Exception ignored) { } pendingListener = null; sendLocationError("مهلت دریافت GPS پایان یافت"); } }, 11000);
        } catch (SecurityException e) { sendLocationError("مجوز مکان‌یابی در دسترس نیست"); }
        catch (Exception e) { sendLocationError("دریافت موقعیت ناموفق بود"); }
    }

    private void sendLocation(Location location) {
        pendingListener = null;
        try {
            JSONObject obj = new JSONObject();
            obj.put("latitude", location.getLatitude()); obj.put("longitude", location.getLongitude());
            obj.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            obj.put("source", location.getProvider() == null ? "Android Location" : location.getProvider());
            sendToJavascript(obj.toString());
        } catch (Exception e) { sendLocationError("ساخت فراداده موقعیت ناموفق بود"); }
    }

    private void sendLocationError(String message) {
        try { JSONObject obj = new JSONObject(); obj.put("error", message); sendToJavascript(obj.toString()); }
        catch (Exception ignored) { sendToJavascript("{\"error\":\"location unavailable\"}"); }
    }

    private void sendToJavascript(String json) {
        final String callback = pendingCallback;
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript("if(window[" + JSONObject.quote(callback) + "])window[" + JSONObject.quote(callback) + "](" + JSONObject.quote(json) + ");", null); });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data == null && pendingCameraUri != null) {
                results = new Uri[]{pendingCameraUri};
            } else if (resultCode == Activity.RESULT_OK && data != null) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            pendingCameraUri = null;
        }
    }

    private void finishBackNavigation() { super.onBackPressed(); }

    @Override public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript("(function(){try{return !!(window.__handleAndroidBack && window.__handleAndroidBack());}catch(e){return false;}})()", value -> {
            if ("true".equals(value)) return;
            if (webView != null && webView.canGoBack()) webView.goBack();
            else finishBackNavigation();
        });
    }
}
