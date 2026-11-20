package ly.fea.system;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * تطبيق «منظومة الكلية»: يبحث تلقائياً عن سيرفر المنظومة على شبكة الـ Wi-Fi ويفتحه،
 * مع دعم الميكروفون (الرسائل الصوتية والمكالمات) ورفع الملفات وتحميلها والطباعة.
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11, REQ_MIC = 12;
    private static final String SHIM =
        "(function(){if(window.__feaShim)return;window.__feaShim=1;" +
        "function b64(b){return new Promise(function(res,rej){var r=new FileReader();r.onload=function(){res(String(r.result).split(',')[1]||'')};r.onerror=rej;r.readAsDataURL(b)})}" +
        "function grab(href,name,open){fetch(href,{credentials:'same-origin'}).then(function(r){var n=name;if(!n){var cd=r.headers.get('Content-Disposition')||'';var m=cd.match(/filename\\*?=(?:UTF-8'')?\"?([^\";]+)/i);n=m?decodeURIComponent(m[1]):'file'}" +
        "return r.blob().then(function(b){return b64(b).then(function(d){if(open)FEAAndroid.openFile(n,b.type||'',d);else FEAAndroid.saveFile(n,b.type||'',d)})})}).catch(function(){FEAAndroid.toast('تعذّر تحميل الملف')})}" +
        "window.__feaFetchSave=function(u,n){grab(u,n,false)};" +
        "function handle(a,ev){var h=a.getAttribute('href')||'';if(!h||h.charAt(0)==='#'||h.indexOf('javascript:')===0)return false;var abs=a.href;" +
        "var same=abs.indexOf(location.origin)===0||abs.indexOf('blob:')===0||abs.indexOf('data:')===0;" +
        "if(a.hasAttribute('download')&&same){if(ev)ev.preventDefault();grab(abs,a.getAttribute('download')||'',false);return true}" +
        "if(a.target==='_blank'&&same&&(abs.indexOf('/api/')>0||abs.indexOf('blob:')===0)){if(ev)ev.preventDefault();grab(abs,'',true);return true}return false}" +
        "var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(handle(this,null))return;return oc.call(this)};" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a'):null;if(a)handle(a,e)},true);" +
        "window.print=function(){FEAAndroid.print(document.title||'FEA')};" +
        "var wo=window.open;window.open=function(u){if(typeof u==='string'&&(u.indexOf('blob:')===0||u.indexOf('/api/')>=0)){grab(new URL(u,location.href).href,'',true);return null}return wo.apply(window,arguments)};" +
        "})();";

    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private LinearLayout panel;
    private TextView status, hint;
    private ProgressBar spin;
    private Button btnRetry, btnManual;
    private Discovery.Server server;
    private PublicKey caKey;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;
    private boolean searching, pageError;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("fea", MODE_PRIVATE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        buildUi();
        watchWifi();
        if (b != null) web.restoreState(b);
        startDiscovery(true);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    // ---------------- الواجهة ----------------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setupWeb();

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(Color.parseColor("#0D3B66"));
        panel.setPadding(dp(28), dp(28), dp(28), dp(28));
        panel.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(24), dp(26), dp(24), dp(22));
        card.setElevation(dp(6));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        card.addView(logo, new LinearLayout.LayoutParams(dp(130), dp(120)));

        TextView title = new TextView(this);
        title.setText("منظومة كلية الاقتصاد والإدارة");
        title.setTextColor(Color.parseColor("#0D3B66"));
        title.setTextSize(20); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(4));
        card.addView(title);

        spin = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(40), dp(40)); sp.topMargin = dp(14);
        card.addView(spin, sp);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#2B4A6B")); status.setTextSize(15); status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, dp(4));
        card.addView(status);

        hint = new TextView(this);
        hint.setTextColor(Color.parseColor("#6B7785")); hint.setTextSize(12.5f); hint.setGravity(Gravity.CENTER);
        card.addView(hint);

        btnRetry = new Button(this); btnRetry.setText("بحث مرة أخرى");
        btnRetry.setOnClickListener(v -> startDiscovery(false));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = dp(14);
        card.addView(btnRetry, bp);

        btnManual = new Button(this); btnManual.setText("إدخال عنوان السيرفر يدوياً");
        btnManual.setOnClickListener(v -> manualDialog());
        card.addView(btnManual, new LinearLayout.LayoutParams(-1, -2));

        TextView credit = new TextView(this);
        credit.setText("System designed by Younes Elhabashi");
        credit.setTextColor(Color.parseColor("#99FFFFFF")); credit.setTextSize(11); credit.setGravity(Gravity.CENTER);

        panel.addView(card, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(18);
        panel.addView(credit, cp);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private void showPanel(String msg, String sub, boolean busy) {
        ui.post(() -> {
            panel.setVisibility(View.VISIBLE);
            status.setText(msg); hint.setText(sub == null ? "" : sub);
            spin.setVisibility(busy ? View.VISIBLE : View.GONE);
            btnRetry.setVisibility(busy ? View.GONE : View.VISIBLE);
            btnManual.setVisibility(busy ? View.GONE : View.VISIBLE);
        });
    }

    private void toast(String m) { ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    // ---------------- البحث عن السيرفر ----------------
    private void startDiscovery(boolean trySaved) {
        if (searching) return;
        searching = true;
        showPanel("جارٍ البحث عن المنظومة على شبكة الـ Wi-Fi…", "تأكد أن الهاتف متصل بشبكة الكلية", true);
        new Thread(() -> {
            try {
                Network wifi = Discovery.wifiNetwork(this);
                if (wifi == null) {
                    showPanel("الهاتف غير متصل بشبكة Wi-Fi", "اتصل بشبكة الـ Wi-Fi الخاصة بالكلية ثم اضغط «بحث مرة أخرى».", false);
                    return;
                }
                cm.bindProcessToNetwork(wifi);   // كل الاتصالات (ومنها صفحة المنظومة) عبر الـ Wi-Fi وليس بيانات الهاتف
                Discovery.Server s = null;
                String saved = prefs.getString("host", null);
                if (trySaved && saved != null) s = Discovery.hello(saved, prefs.getInt("http", Discovery.DEFAULT_HTTP), 1500);
                if (s == null) { showPanel("جارٍ البحث عن المنظومة على شبكة الـ Wi-Fi…", "إرسال نداء البحث", true); s = Discovery.udp(this, wifi, 2500); }
                if (s == null && saved != null && !trySaved) s = Discovery.hello(saved, prefs.getInt("http", Discovery.DEFAULT_HTTP), 1500);
                if (s == null) { showPanel("جارٍ البحث عن المنظومة على شبكة الـ Wi-Fi…", "فحص أجهزة الشبكة (قد يستغرق بضع ثوانٍ)", true); s = Discovery.scan(this, wifi, prefs.getInt("http", Discovery.DEFAULT_HTTP)); }
                if (s == null) {
                    showPanel("لم يتم العثور على المنظومة", "تأكد أن السيرفر يعمل وأن الهاتف على نفس الشبكة، أو أدخل عنوان السيرفر يدوياً.", false);
                    return;
                }
                connect(s);
            } finally { searching = false; }
        }).start();
    }

    /** يُستدعى من خيط خلفي بعد العثور على السيرفر. */
    private void connect(Discovery.Server s) {
        if (s.https > 0) {
            caKey = loadCa(s);
            if (caKey == null) {   // تعذّر قراءة شهادة السيرفر المحلية: نكتفي بالاتصال العادي
                s.https = 0;
                toast("تعذّر تفعيل الاتصال الآمن؛ الرسائل الصوتية والمكالمات قد لا تعمل.");
            }
        }
        server = s;
        prefs.edit().putString("host", s.host).putInt("http", s.http).putInt("https", s.https).apply();
        showPanel("تم العثور على المنظومة", s.host, true);
        ui.post(() -> { pageError = false; web.loadUrl(s.baseUrl()); });
    }

    /** شهادة المنظومة المحلية (CA): تُقرأ من السيرفر وتُحفظ، وتُستخدم للتحقق من الاتصال الآمن. */
    private PublicKey loadCa(Discovery.Server s) {
        String key = "ca_" + s.host;
        String b64 = prefs.getString(key, null);
        try {
            byte[] der = null;
            if (b64 == null) {
                der = Discovery.httpGetBytes("http://" + s.host + ":" + s.http + "/fea-ca.crt", 4000);
                if (der == null) return null;
            } else der = Base64.decode(b64, Base64.DEFAULT);
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            if (b64 == null) prefs.edit().putString(key, Base64.encodeToString(ca.getEncoded(), Base64.NO_WRAP)).apply();
            return ca.getPublicKey();
        } catch (Exception e) { return null; }
    }

    private static X509Certificate toX509(SslCertificate c) {
        try {
            Bundle b = SslCertificate.saveState(c);
            byte[] der = b.getByteArray("x509-certificate");
            if (der == null) return null;
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) { return null; }
    }

    private void manualDialog() {
        final EditText in = new EditText(this);
        in.setHint("مثال: 192.168.1.10");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String saved = prefs.getString("host", "");
        in.setText(saved);
        new AlertDialog.Builder(this).setTitle("عنوان سيرفر المنظومة").setView(in)
            .setPositiveButton("اتصال", (d, w) -> {
                String t = in.getText().toString().trim().replace("http://", "").replace("https://", "");
                if (t.endsWith("/")) t = t.substring(0, t.length() - 1);
                String host = t; int port = Discovery.DEFAULT_HTTP;
                int i = t.lastIndexOf(':');
                if (i > 0) { host = t.substring(0, i); try { port = Integer.parseInt(t.substring(i + 1)); } catch (Exception ignored) { } }
                final String h = host; final int p = port;
                showPanel("جارٍ الاتصال بـ " + h + "…", null, true);
                new Thread(() -> {
                    Network wifi = Discovery.wifiNetwork(this);
                    if (wifi != null) cm.bindProcessToNetwork(wifi);
                    Discovery.Server s = Discovery.hello(h, p, 3000);
                    if (s == null) showPanel("لم تستجب المنظومة على هذا العنوان", h + ":" + p, false);
                    else connect(s);
                }).start();
            })
            .setNegativeButton("إلغاء", null).show();
    }

    private void watchWifi() {
        NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                ui.postDelayed(() -> { if (pageError || panel.getVisibility() == View.VISIBLE) startDiscovery(true); }, 1500);
            }
            @Override public void onLost(Network n) {
                if (Discovery.wifiNetwork(MainActivity.this) == null)
                    showPanel("انقطع الاتصال بشبكة الـ Wi-Fi", "سيعود الاتصال تلقائياً عند الرجوع إلى شبكة الكلية.", false);
            }
        });
    }

    // ---------------- WebView ----------------
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " FEA-Android/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new Bridge(), "FEAAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                // نقبل فقط شهادة السيرفر الموقَّعة بشهادة المنظومة المحلية المحفوظة، ولنفس عنوان السيرفر
                X509Certificate leaf = toX509(e.getCertificate());
                boolean ok = false;
                try {
                    Uri u = Uri.parse(e.getUrl());
                    if (leaf != null && caKey != null && server != null && server.host.equals(u.getHost())) { leaf.verify(caKey); leaf.checkValidity(); ok = true; }
                } catch (Exception ignored) { }
                if (ok) h.proceed(); else { h.cancel(); toast("تم رفض اتصال غير موثوق"); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (server != null && server.host.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(SHIM, null);
                if (!pageError && server != null && url != null && url.contains(server.host)) panel.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    pageError = true;
                    showPanel("تعذّر الاتصال بالمنظومة", "تأكد أن السيرفر يعمل وأن الهاتف متصل بشبكة الكلية.", false);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) {
                ui.post(() -> {
                    boolean wantsMic = false;
                    for (String res : r.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) wantsMic = true;
                    if (!wantsMic) { r.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    else { pendingPerm = r; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC); }
                });
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = p.createIntent();
                    if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, "اختر ملفاً"), REQ_FILE);
                } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            String js = "window.__feaFetchSave&&window.__feaFetchSave(" + org.json.JSONObject.quote(url) + ",'')";
            web.evaluateJavascript(js, null);
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb == null) return;
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    List<Uri> l = new ArrayList<>();
                    for (int i = 0; i < clip.getItemCount(); i++) l.add(clip.getItemAt(i).getUri());
                    out = l.toArray(new Uri[0]);
                } else if (data.getData() != null) out = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(out);
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_MIC && pendingPerm != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else { pendingPerm.deny(); toast("الرسائل الصوتية والمكالمات تحتاج إذن الميكروفون"); }
            pendingPerm = null;
        }
    }

    @Override public void onBackPressed() {
        if (panel.getVisibility() != View.VISIBLE && web.canGoBack()) { web.goBack(); return; }
        new AlertDialog.Builder(this).setTitle("منظومة الكلية")
            .setItems(new String[]{"إعادة البحث عن السيرفر", "إدخال عنوان السيرفر يدوياً", "خروج من التطبيق"}, (d, w) -> {
                if (w == 0) startDiscovery(false); else if (w == 1) manualDialog(); else finish();
            }).setNegativeButton("إلغاء", null).show();
    }

    // ---------------- الجسر مع صفحة المنظومة ----------------
    private static String safeName(String n) {
        if (n == null || n.trim().isEmpty()) n = "file";
        n = n.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    private static String mimeOf(String name, String mime) {
        if (mime != null && !mime.isEmpty() && !mime.equals("application/octet-stream")) return mime;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        String t = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return t != null ? t : "application/octet-stream";
    }

    private File writeShared(String name, byte[] data) throws Exception {
        File dir = new File(getCacheDir(), "shared");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, safeName(name));
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        return f;
    }

    private void openWith(Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "فتح الملف")); }
        catch (Exception e) { toast("لا يوجد تطبيق على الهاتف لفتح هذا النوع من الملفات"); }
    }

    private final class Bridge {
        @JavascriptInterface public void toast(String m) { MainActivity.this.toast(m); }

        @JavascriptInterface public String appVersion() { return "1.0"; }

        @JavascriptInterface public void openFile(String name, String mime, String b64) {
            try {
                name = safeName(name); mime = mimeOf(name, mime);
                File f = writeShared(name, Base64.decode(b64, Base64.DEFAULT));
                final String m = mime;
                ui.post(() -> openWith(SharedFiles.uriFor(f), m));
            } catch (Exception e) { MainActivity.this.toast("تعذّر فتح الملف"); }
        }

        @JavascriptInterface public void saveFile(String name, String mime, String b64) {
            try {
                name = safeName(name); mime = mimeOf(name, mime);
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                Uri saved;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FEA");
                    saved = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (saved == null) throw new Exception();
                    try (OutputStream o = cr.openOutputStream(saved)) { o.write(data); }
                    MainActivity.this.toast("تم حفظ الملف في التنزيلات/FEA: " + name);
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                    saved = SharedFiles.uriFor(writeShared(name, data));
                    MainActivity.this.toast("تم حفظ الملف: " + f.getAbsolutePath());
                }
                final Uri u = saved; final String m = mime;
                ui.post(() -> openWith(u, m));
            } catch (Exception e) { MainActivity.this.toast("تعذّر حفظ الملف"); }
        }

        @JavascriptInterface public void print(String title) {
            ui.post(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                String job = (title == null || title.isEmpty()) ? "FEA" : title;
                pm.print(job, web.createPrintDocumentAdapter(job), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
            });
        }
    }
}
