package vn.quang.trinhduyet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

public class MainActivity extends Activity {

    static final String HOME = "file:///android_asset/home.html";
    static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final int GREEN = 0xFF2E7D32;
    static final int BAR_BG = 0xFFF1F3F4;
    static final int REQ_FILE = 11;
    static final long WEEK = 7L * 24 * 3600 * 1000;

    final class Tab {
        WebView wv;
        volatile String pageHost = "";
        volatile boolean ab = true;
        final AtomicInteger blocked = new AtomicInteger();
        boolean desktop;
        String title = "Tab mới";
        String pendingUrl; // tab khôi phục, chưa tải
    }

    final class Bridge {
        final Tab t;
        Bridge(Tab t) { this.t = t; }
        @JavascriptInterface public boolean on(String host) { return t.ab; }
        @JavascriptInterface public long total() { return totalBlocked.get(); }
    }

    final Handler main = new Handler(Looper.getMainLooper());
    final List<Tab> tabs = new ArrayList<>();
    Tab cur;
    SharedPreferences prefs;
    volatile Set<String> allowSites = new HashSet<>();
    volatile boolean globalOn = true;
    final AtomicLong totalBlocked = new AtomicLong();
    String injectJs = "";
    String mobileUA;

    EditText urlBar;
    TextView shieldBtn, tabsBtn, menuBtn, popupText;
    ProgressBar progress;
    FrameLayout content, fullscreenBox;
    LinearLayout popupBar;
    String popupUrl;
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    ValueCallback<Uri[]> fileCb;
    boolean shieldPosted;
    final Runnable hidePopup = () -> { if (popupBar != null) popupBar.setVisibility(View.GONE); };

    // ------------------------------------------------------------------ vòng đời

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("td", MODE_PRIVATE);
        allowSites = new HashSet<>(prefs.getStringSet("allow", Collections.emptySet()));
        globalOn = prefs.getBoolean("on", true);
        totalBlocked.set(prefs.getLong("total", 0));
        injectJs = readAsset("inject.js");
        try {
            mobileUA = WebSettings.getDefaultUserAgent(this)
                    .replace("; wv", "")
                    .replaceAll(" Version/\\d+(\\.\\d+)*", "");
        } catch (Exception e) {
            mobileUA = null;
        }

        new Thread(() -> {
            AdBlocker.load(getApplicationContext());
            long last = prefs.getLong("upd", 0);
            if (System.currentTimeMillis() - last > WEEK) {
                if (AdBlocker.update(getApplicationContext()) > 0) {
                    prefs.edit().putLong("upd", System.currentTimeMillis()).apply();
                }
            }
        }).start();

        buildUi();
        restoreTabs();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTabs();
        CookieManager.getInstance().flush();
        if (cur != null && customView == null) cur.wv.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cur != null) cur.wv.onResume();
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) {
            try { t.wv.destroy(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) { hideCustomView(); return; }
        if (urlBar.hasFocus()) { urlBar.clearFocus(); hideKeyboard(); return; }
        if (popupBar.getVisibility() == View.VISIBLE) { popupBar.setVisibility(View.GONE); return; }
        if (cur != null && cur.wv.canGoBack()) { cur.wv.goBack(); return; }
        if (tabs.size() > 1) { closeTab(cur); return; }
        moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    // ------------------------------------------------------------------ giao diện

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    TextView barButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(17);
        v.setTextColor(0xFF3C4043);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(40));
        v.setPadding(dp(8), 0, dp(8), 0);
        v.setBackgroundResource(android.R.drawable.list_selector_background);
        return v;
    }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(BAR_BG);
        top.setPadding(dp(4), dp(6), dp(4), dp(6));

        shieldBtn = barButton("🛡");
        shieldBtn.setTextSize(14);
        shieldBtn.setOnClickListener(v -> showShieldDialog());
        top.addView(shieldBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(15);
        urlBar.setHint("Tìm kiếm hoặc nhập địa chỉ web");
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setPadding(dp(14), 0, dp(14), 0);
        urlBar.setBackground(round(Color.WHITE, 20, 0));
        urlBar.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE || enter) {
                navigate(urlBar.getText().toString());
                urlBar.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });
        urlBar.setOnFocusChangeListener((v, has) -> { if (!has) showUrl(); });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(40), 1);
        ulp.setMargins(dp(2), 0, dp(2), 0);
        top.addView(urlBar, ulp);

        tabsBtn = barButton("1");
        tabsBtn.setTextSize(13);
        tabsBtn.setTypeface(Typeface.DEFAULT_BOLD);
        tabsBtn.setOnClickListener(v -> showTabsDialog());
        LinearLayout tabsWrap = new LinearLayout(this);
        tabsWrap.setGravity(Gravity.CENTER);
        TextView box = tabsBtn;
        box.setBackground(round(Color.TRANSPARENT, 5, 0xFF3C4043));
        box.setMinWidth(dp(26));
        box.setPadding(dp(4), 0, dp(4), 0);
        tabsWrap.addView(box, new LinearLayout.LayoutParams(-2, dp(26)));
        tabsWrap.setPadding(dp(8), 0, dp(8), 0);
        tabsWrap.setOnClickListener(v -> showTabsDialog());
        top.addView(tabsWrap, new LinearLayout.LayoutParams(-2, dp(40)));

        menuBtn = barButton("⋮");
        menuBtn.setTextSize(22);
        menuBtn.setOnClickListener(v -> showMenu());
        top.addView(menuBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(GREEN));
        progress.setVisibility(View.INVISIBLE);
        col.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        content = new FrameLayout(this);
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        popupBar = new LinearLayout(this);
        popupBar.setOrientation(LinearLayout.HORIZONTAL);
        popupBar.setGravity(Gravity.CENTER_VERTICAL);
        popupBar.setBackgroundColor(0xFF323232);
        popupBar.setPadding(dp(14), dp(8), dp(6), dp(8));
        popupText = new TextView(this);
        popupText.setTextColor(Color.WHITE);
        popupText.setTextSize(13);
        popupBar.addView(popupText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView open = barButton("Mở");
        open.setTextColor(0xFF81C784);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setOnClickListener(v -> {
            popupBar.setVisibility(View.GONE);
            if (popupUrl != null) openLinkOrApp(popupUrl);
        });
        popupBar.addView(open, new LinearLayout.LayoutParams(-2, dp(40)));
        TextView close = barButton("✕");
        close.setTextColor(Color.WHITE);
        close.setOnClickListener(v -> popupBar.setVisibility(View.GONE));
        popupBar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        popupBar.setVisibility(View.GONE);
        col.addView(popupBar, new LinearLayout.LayoutParams(-1, -2));

        fullscreenBox = new FrameLayout(this);
        fullscreenBox.setBackgroundColor(Color.BLACK);
        fullscreenBox.setVisibility(View.GONE);
        root.addView(fullscreenBox, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    void showUrl() {
        if (cur == null || urlBar.hasFocus()) return;
        String u = cur.pendingUrl != null ? cur.pendingUrl : cur.wv.getUrl();
        urlBar.setText(u == null || u.startsWith(HOME) ? "" : u);
    }

    void updateShield() {
        if (cur == null) return;
        if (!cur.ab) {
            shieldBtn.setText("🛡 tắt");
            shieldBtn.setTextColor(0xFF9E9E9E);
        } else {
            int n = cur.blocked.get();
            shieldBtn.setText(n > 0 ? "🛡 " + n : "🛡");
            shieldBtn.setTextColor(GREEN);
        }
    }

    /** Gọi từ luồng nền: cập nhật số trên tấm khiên, không quá dày. */
    void postShield() {
        if (shieldPosted) return;
        shieldPosted = true;
        main.postDelayed(() -> { shieldPosted = false; updateShield(); }, 400);
    }

    void updateTabsBtn() {
        tabsBtn.setText(tabs.size() > 99 ? ":D" : String.valueOf(tabs.size()));
    }

    void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void showPopupBar(String text, String url) {
        popupUrl = url;
        popupText.setText(text);
        popupBar.setVisibility(View.VISIBLE);
        main.removeCallbacks(hidePopup);
        main.postDelayed(hidePopup, 7000);
    }

    // ------------------------------------------------------------------ điều hướng

    void navigate(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty() || cur == null) return;
        String url;
        if (s.matches("(?i)^[a-z][a-z0-9+.-]*://.*") || s.startsWith("about:")) {
            url = s;
        } else if (!s.contains(" ") && ((s.contains(".") && Patterns.WEB_URL.matcher(s).matches())
                || s.startsWith("localhost"))) {
            url = "https://" + s;
        } else {
            url = searchUrl(s);
        }
        load(cur, url);
    }

    String searchUrl(String q) {
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + Uri.encode(q);
        }
    }

    void load(Tab t, String url) {
        t.pendingUrl = null;
        t.wv.loadUrl(url);
    }

    void handleIntent(Intent i) {
        if (i == null) return;
        String act = i.getAction();
        String url = null;
        if (Intent.ACTION_VIEW.equals(act) && i.getData() != null) {
            url = i.getDataString();
        } else if (Intent.ACTION_WEB_SEARCH.equals(act)) {
            String q = i.getStringExtra(SearchManager.QUERY);
            if (q != null) url = q.trim().matches("(?i)^https?://.*") ? q.trim() : searchUrl(q);
        } else if (Intent.ACTION_SEND.equals(act)) {
            String txt = i.getStringExtra(Intent.EXTRA_TEXT);
            if (txt != null) {
                Matcher m = Patterns.WEB_URL.matcher(txt);
                url = m.find() ? m.group() : searchUrl(txt);
                if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
            }
        }
        if (url != null) {
            i.setAction(null);
            createTab(url, true, true);
        }
    }

    boolean openExternal(String url) {
        try {
            Intent it;
            if (url.startsWith("intent:")) {
                it = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                it.addCategory(Intent.CATEGORY_BROWSABLE);
                it.setComponent(null);
                it.setSelector(null);
                try {
                    startActivity(it);
                    return true;
                } catch (ActivityNotFoundException e) {
                    String fb = it.getStringExtra("browser_fallback_url");
                    if (fb != null && fb.startsWith("http")) {
                        load(cur, fb);
                        return true;
                    }
                    if (it.getPackage() != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=" + it.getPackage())));
                        return true;
                    }
                    throw e;
                }
            }
            it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(it);
            return true;
        } catch (Exception e) {
            toast("Không có ứng dụng để mở liên kết này");
            return false;
        }
    }

    void openLinkOrApp(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) createTab(url, true, true);
        else openExternal(url);
    }

    boolean abFor(String host) {
        if (!globalOn) return false;
        if (host == null || host.isEmpty()) return true;
        return !allowSites.contains(siteKey(host));
    }

    static String siteKey(String host) {
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    // ------------------------------------------------------------------ tab

    Tab createTab(String url, boolean switchTo, boolean loadNow) {
        Tab t = new Tab();
        t.wv = new WebView(this);
        setupWebView(t);
        tabs.add(t);
        if (url == null || url.isEmpty()) url = HOME;
        if (loadNow) {
            t.wv.loadUrl(url);
        } else {
            t.pendingUrl = url;
            t.title = url.startsWith(HOME) ? "Trang chủ" : url;
        }
        if (switchTo) showTab(t);
        updateTabsBtn();
        return t;
    }

    void showTab(Tab t) {
        if (cur != null && cur != t) cur.wv.onPause();
        content.removeAllViews();
        content.addView(t.wv, new FrameLayout.LayoutParams(-1, -1));
        cur = t;
        t.wv.onResume();
        if (t.pendingUrl != null) load(t, t.pendingUrl);
        progress.setVisibility(View.INVISIBLE);
        showUrl();
        updateShield();
        updateTabsBtn();
    }

    void closeTab(Tab t) {
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        tabs.remove(idx);
        if (t == cur) {
            content.removeAllViews();
            cur = null;
        }
        try { t.wv.destroy(); } catch (Exception ignored) {}
        if (tabs.isEmpty()) {
            createTab(HOME, true, true);
        } else if (cur == null) {
            showTab(tabs.get(Math.min(Math.max(0, idx - 1), tabs.size() - 1)));
        }
        updateTabsBtn();
    }

    void saveTabs() {
        JSONArray a = new JSONArray();
        for (Tab t : tabs) {
            String u = t.pendingUrl != null ? t.pendingUrl : t.wv.getUrl();
            a.put(u == null ? HOME : u);
        }
        prefs.edit()
                .putString("tabs", a.toString())
                .putInt("cur", Math.max(0, tabs.indexOf(cur)))
                .putLong("total", totalBlocked.get())
                .apply();
    }

    void restoreTabs() {
        try {
            JSONArray a = new JSONArray(prefs.getString("tabs", "[]"));
            int c = prefs.getInt("cur", 0);
            for (int i = 0; i < a.length(); i++) createTab(a.getString(i), false, false);
            if (!tabs.isEmpty()) showTab(tabs.get(Math.min(Math.max(c, 0), tabs.size() - 1)));
        } catch (Exception ignored) {}
        if (tabs.isEmpty()) createTab(HOME, true, true);
    }

    void showTabsDialog() {
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(list);
        final AlertDialog[] dlg = new AlertDialog[1];
        for (Tab t : new ArrayList<>(tabs)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(4), dp(4), dp(4));
            row.setBackground(round(t == cur ? 0xFFE8F5E9 : 0xFFF5F5F5, 10, t == cur ? GREEN : 0));
            TextView title = new TextView(this);
            String u = t.pendingUrl != null ? t.pendingUrl : t.wv.getUrl();
            String host = u == null || u.startsWith(HOME) ? "Trang chủ" : Uri.parse(u).getHost();
            title.setText((TextUtils.isEmpty(t.title) ? "Tab" : t.title) + "\n" + (host == null ? "" : host));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextSize(14);
            title.setTextColor(0xFF202124);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = barButton("✕");
            row.addView(x, new LinearLayout.LayoutParams(dp(44), dp(44)));
            row.setOnClickListener(v -> { showTab(t); dlg[0].dismiss(); });
            title.setOnClickListener(v -> { showTab(t); dlg[0].dismiss(); });
            x.setOnClickListener(v -> {
                closeTab(t);
                dlg[0].dismiss();
                if (tabs.size() > 1) showTabsDialog();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            list.addView(row, lp);
        }
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("Các tab (" + tabs.size() + ")")
                .setView(sv)
                .setPositiveButton("+ Tab mới", (d, w) -> createTab(HOME, true, true))
                .setNeutralButton("Đóng tất cả", (d, w) -> {
                    for (Tab t : new ArrayList<>(tabs)) {
                        try { t.wv.destroy(); } catch (Exception ignored) {}
                    }
                    tabs.clear();
                    content.removeAllViews();
                    cur = null;
                    createTab(HOME, true, true);
                })
                .setNegativeButton("Xong", null)
                .create();
        dlg[0].show();
    }

    // ------------------------------------------------------------------ WebView

    static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    WebResourceResponse blockedPage(String host) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding:60px 20px;color:#444'>"
                + "<div style='font-size:48px'>🛡</div><h3>Đã chặn trang quảng cáo</h3><p>"
                + TextUtils.htmlEncode(host == null ? "" : host) + "</p></body></html>";
        return new WebResourceResponse("text/html", "utf-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    void countBlocked(Tab t) {
        t.blocked.incrementAndGet();
        totalBlocked.incrementAndGet();
        if (t == cur) postShield();
    }

    void setupWebView(final Tab t) {
        final WebView wv = t.wv;
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        if (mobileUA != null) s.setUserAgentString(mobileUA);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.addJavascriptInterface(new Bridge(t), "QuangAB");

        final boolean docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (docStart) {
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, injectJs, Collections.singleton("*"));
            } catch (Exception ignored) {}
        }

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if (r.isForMainFrame()) {
                    String h = u.getHost();
                    t.pageHost = h == null ? "" : h;
                    t.ab = abFor(t.pageHost);
                    t.blocked.set(0);
                    if (t == cur) postShield();
                    if (t.ab && (sc.equals("http") || sc.equals("https")) && AdBlocker.isAdHost(h)) {
                        countBlocked(t);
                        return blockedPage(h);
                    }
                    return null;
                }
                if (!t.ab) return null;
                if ((sc.equals("http") || sc.equals("https")) && AdBlocker.isAd(u)) {
                    countBlocked(t);
                    return emptyResponse();
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if (sc.equals("http") || sc.equals("https")) {
                    if (r.isForMainFrame() && abFor(t.pageHost) && AdBlocker.isAdHost(u.getHost())) {
                        countBlocked(t);
                        if (t == cur) showPopupBar("Đã chặn chuyển sang trang quảng cáo\n" + u.getHost(), u.toString());
                        return true;
                    }
                    return false;
                }
                if (sc.equals("about") || sc.equals("javascript") || sc.equals("data")
                        || sc.equals("blob") || sc.equals("file")) {
                    return false;
                }
                // Liên kết mở app khác (Play Store, Zalo, Shopee...)
                if (!r.isForMainFrame()) return true;
                if (!r.hasGesture() && abFor(t.pageHost)) {
                    countBlocked(t);
                    if (t == cur) showPopupBar("Đã chặn trang tự mở ứng dụng khác", u.toString());
                    return true;
                }
                openExternal(u.toString());
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap fav) {
                if (!docStart && t.ab) v.evaluateJavascript(injectJs, null);
                if (t == cur) {
                    progress.setVisibility(View.VISIBLE);
                    showUrl();
                    updateShield();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!docStart && t.ab) v.evaluateJavascript(injectJs, null);
                if (t == cur) {
                    progress.setVisibility(View.INVISIBLE);
                    showUrl();
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                if (t == cur) showUrl();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                String url = v.getUrl();
                int idx = tabs.indexOf(t);
                if (idx >= 0) {
                    boolean wasCur = t == cur;
                    if (wasCur) { content.removeAllViews(); cur = null; }
                    tabs.remove(idx);
                    try { v.destroy(); } catch (Exception ignored) {}
                    Tab n = createTab(url, false, true);
                    tabs.remove(n);
                    tabs.add(idx, n);
                    if (wasCur || cur == null) showTab(n);
                    toast("Trang bị lỗi, đã tải lại");
                }
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (t != cur) return;
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                t.title = title;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                showCustomView(view, cb);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                return capturePopup(t, view, isUserGesture, resultMsg);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    toast("Không mở được trình chọn tệp");
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }
        });

        wv.setDownloadListener((url, ua, cd, mime, len) -> download(url, ua, cd, mime));
        wv.setOnLongClickListener(v -> onLongPress(t));
    }

    /** Cửa sổ bật lên: bắt lấy địa chỉ, chỉ mở khi đúng là liên kết anh vừa bấm. */
    boolean capturePopup(final Tab src, WebView view, final boolean gesture, Message resultMsg) {
        WebView.HitTestResult hr = view.getHitTestResult();
        final String tapped = (hr != null && hr.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) ? hr.getExtra() : null;
        final WebView tmp = new WebView(this);
        final boolean[] done = {false};
        tmp.getSettings().setJavaScriptEnabled(false);
        tmp.setWebViewClient(new WebViewClient() {
            void got(String u) {
                if (done[0] || u == null || u.isEmpty() || u.startsWith("about:")) return;
                done[0] = true;
                try { tmp.stopLoading(); } catch (Exception ignored) {}
                onPopupUrl(src, u, tapped, gesture);
                main.post(() -> { try { tmp.destroy(); } catch (Exception ignored) {} });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                got(r.getUrl().toString());
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap f) {
                got(u);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (r.isForMainFrame()) {
                    final String u = r.getUrl().toString();
                    main.post(() -> got(u));
                }
                return emptyResponse();
            }
        });
        WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
        tr.setWebView(tmp);
        resultMsg.sendToTarget();
        main.postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                try { tmp.destroy(); } catch (Exception ignored) {}
            }
        }, 5000);
        return true;
    }

    static String norm(String u) {
        if (u == null) return "";
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        u = u.replaceFirst("(?i)^https?://(www\\.)?", "");
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.toLowerCase(Locale.ROOT);
    }

    void onPopupUrl(Tab src, String u, String tapped, boolean gesture) {
        Uri uri = Uri.parse(u);
        boolean web = u.startsWith("http://") || u.startsWith("https://");
        if (!src.ab) {
            openLinkOrApp(u);
            return;
        }
        if (web && AdBlocker.isAd(uri)) {
            countBlocked(src);
            return;
        }
        if (web && gesture && tapped != null && norm(tapped).equals(norm(u))) {
            createTab(u, true, true);
            return;
        }
        countBlocked(src);
        String host = uri.getHost();
        showPopupBar("Đã chặn cửa sổ bật lên\n" + (host == null ? u : host), u);
    }

    // ------------------------------------------------------------------ toàn màn hình

    void showCustomView(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = view;
        customCb = cb;
        fullscreenBox.addView(view, new FrameLayout.LayoutParams(-1, -1));
        fullscreenBox.setVisibility(View.VISIBLE);
        setSystemBars(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (cur != null) {
            String js = "(function(){var e=document.fullscreenElement||document.webkitFullscreenElement;"
                    + "var v=e&&e.tagName==='VIDEO'?e:(e&&e.querySelector?e.querySelector('video'):null);"
                    + "if(!v)v=document.querySelector('video');return v?v.videoWidth+'x'+v.videoHeight:'';})()";
            cur.wv.evaluateJavascript(js, val -> {
                if (customView == null || val == null) return;
                String s2 = val.replace("\"", "");
                String[] p = s2.split("x");
                try {
                    int w = Integer.parseInt(p[0]);
                    int h = Integer.parseInt(p[1]);
                    if (w > 0 && h > 0) {
                        setRequestedOrientation(w >= h ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    void hideCustomView() {
        if (customView == null) return;
        fullscreenBox.removeView(customView);
        fullscreenBox.setVisibility(View.GONE);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        setSystemBars(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @SuppressWarnings("deprecation")
    void setSystemBars(boolean show) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (show) {
                c.show(WindowInsets.Type.systemBars());
            } else {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(show ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ------------------------------------------------------------------ tải về, bấm giữ

    void download(String url, String ua, String cd, String mime) {
        if (url == null || url.startsWith("blob:") || url.startsWith("data:")) {
            toast("Không tải được tệp này");
            return;
        }
        final String name = URLUtil.guessFileName(url, cd, mime);
        new AlertDialog.Builder(this)
                .setTitle("Tải tệp về máy?")
                .setMessage(name)
                .setPositiveButton("Tải về", (d, w) -> {
                    try {
                        DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                        if (mime != null) rq.setMimeType(mime);
                        if (ua != null) rq.addRequestHeader("User-Agent", ua);
                        String ck = CookieManager.getInstance().getCookie(url);
                        if (ck != null) rq.addRequestHeader("Cookie", ck);
                        rq.setTitle(name);
                        rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        dm.enqueue(rq);
                        toast("Đang tải: " + name + " (thư mục Download)");
                    } catch (Exception e) {
                        toast("Lỗi tải về: " + e.getMessage());
                    }
                })
                .setNegativeButton("Huỷ", null)
                .show();
    }

    boolean onLongPress(Tab t) {
        WebView.HitTestResult h = t.wv.getHitTestResult();
        if (h == null) return false;
        final int type = h.getType();
        final String extra = h.getExtra();
        if (extra == null) return false;
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            linkMenu(extra, null);
            return true;
        }
        if (type == WebView.HitTestResult.IMAGE_TYPE) {
            linkMenu(null, extra);
            return true;
        }
        if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            Message msg = new Handler(Looper.getMainLooper(), m -> {
                String href = m.getData() == null ? null : m.getData().getString("url");
                linkMenu(href, extra);
                return true;
            }).obtainMessage();
            t.wv.requestFocusNodeHref(msg);
            return true;
        }
        return false;
    }

    void linkMenu(final String link, final String img) {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (link != null) {
            items.add("Mở trong tab mới");
            acts.add(() -> openLinkOrApp(link));
            items.add("Mở trong tab nền");
            acts.add(() -> { createTab(link, false, false); toast("Đã mở trong tab nền"); });
            items.add("Sao chép liên kết");
            acts.add(() -> copy(link));
            items.add("Chia sẻ liên kết");
            acts.add(() -> share(link));
        }
        if (img != null && (img.startsWith("http://") || img.startsWith("https://"))) {
            items.add("Tải ảnh về máy");
            acts.add(() -> download(img, mobileUA, null, null));
            items.add("Mở ảnh trong tab mới");
            acts.add(() -> createTab(img, true, true));
        }
        if (items.isEmpty()) return;
        String title = link != null ? link : img;
        new AlertDialog.Builder(this)
                .setTitle(title.length() > 80 ? title.substring(0, 80) + "…" : title)
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", s));
        toast("Đã sao chép");
    }

    void share(String s) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(i, "Chia sẻ"));
    }

    // ------------------------------------------------------------------ menu, chặn QC

    void showShieldDialog() {
        if (cur == null) return;
        final String key = siteKey(cur.pageHost);
        boolean siteOff = !key.isEmpty() && allowSites.contains(key);
        String msg;
        if (!globalOn) msg = "Chặn quảng cáo đang TẮT cho mọi trang (bật lại trong menu ⋮).";
        else if (siteOff) msg = "Chặn quảng cáo đang tắt cho trang " + key + ".";
        else msg = "Đã chặn " + cur.blocked.get() + " quảng cáo trên trang này.\nTổng cộng: "
                + String.format(Locale.US, "%,d", totalBlocked.get()).replace(',', '.') + " quảng cáo.";
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("🛡 Chặn quảng cáo").setMessage(msg)
                .setNegativeButton("Đóng", null);
        if (globalOn && !key.isEmpty()) {
            b.setPositiveButton(siteOff ? "Bật lại cho trang này" : "Tắt cho trang này", (d, w) -> {
                Set<String> n = new HashSet<>(allowSites);
                if (siteOff) n.remove(key); else n.add(key);
                allowSites = n;
                prefs.edit().putStringSet("allow", n).apply();
                cur.ab = abFor(cur.pageHost);
                cur.wv.reload();
                updateShield();
            });
        }
        b.show();
    }

    void showMenu() {
        if (cur == null) return;
        PopupMenu pm = new PopupMenu(this, menuBtn);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "← Quay lại").setEnabled(cur.wv.canGoBack());
        m.add(0, 2, 0, "→ Tiến tới").setEnabled(cur.wv.canGoForward());
        m.add(0, 3, 0, "⟳ Tải lại trang");
        m.add(0, 4, 0, "⌂ Trang chủ");
        m.add(0, 5, 0, "+ Tab mới");
        m.add(0, 6, 0, "☆ Thêm vào dấu trang");
        m.add(0, 7, 0, "★ Dấu trang");
        m.add(0, 8, 0, (cur.desktop ? "✓ " : "") + "🖥 Xem bản máy tính");
        m.add(0, 9, 0, "Chia sẻ trang");
        m.add(0, 10, 0, globalOn ? "🛡 Tắt chặn quảng cáo (mọi trang)" : "🛡 Bật chặn quảng cáo");
        m.add(0, 11, 0, "Cập nhật danh sách chặn");
        m.add(0, 12, 0, "Xoá dữ liệu duyệt web");
        m.add(0, 13, 0, "Thoát");
        pm.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: cur.wv.goBack(); break;
                case 2: cur.wv.goForward(); break;
                case 3: cur.wv.reload(); break;
                case 4: load(cur, HOME); break;
                case 5: createTab(HOME, true, true); break;
                case 6: addBookmark(); break;
                case 7: showBookmarks(); break;
                case 8: toggleDesktop(); break;
                case 9: {
                    String u = cur.wv.getUrl();
                    if (u != null && !u.startsWith(HOME)) share(u);
                    break;
                }
                case 10: {
                    globalOn = !globalOn;
                    prefs.edit().putBoolean("on", globalOn).apply();
                    cur.ab = abFor(cur.pageHost);
                    cur.wv.reload();
                    toast(globalOn ? "Đã bật chặn quảng cáo" : "Đã tắt chặn quảng cáo");
                    break;
                }
                case 11: updateList(); break;
                case 12: clearData(); break;
                case 13: saveTabs(); finishAndRemoveTask(); break;
            }
            return true;
        });
        pm.show();
    }

    void toggleDesktop() {
        cur.desktop = !cur.desktop;
        WebSettings s = cur.wv.getSettings();
        s.setUserAgentString(cur.desktop ? DESKTOP_UA : mobileUA);
        cur.wv.reload();
        toast(cur.desktop ? "Đang xem bản máy tính" : "Đang xem bản điện thoại");
    }

    void updateList() {
        toast("Đang tải danh sách chặn mới…");
        new Thread(() -> {
            int n = AdBlocker.update(getApplicationContext());
            if (n > 0) prefs.edit().putLong("upd", System.currentTimeMillis()).apply();
            main.post(() -> toast(n > 0
                    ? "Đã cập nhật: " + String.format(Locale.US, "%,d", n).replace(',', '.') + " tên miền quảng cáo"
                    : "Không cập nhật được, anh kiểm tra mạng rồi thử lại"));
        }).start();
    }

    void clearData() {
        new AlertDialog.Builder(this)
                .setTitle("Xoá dữ liệu duyệt web?")
                .setMessage("Xoá bộ nhớ đệm, cookie (sẽ bị đăng xuất khỏi các trang) và lịch sử các tab.")
                .setPositiveButton("Xoá", (d, w) -> {
                    for (Tab t : tabs) {
                        t.wv.clearCache(true);
                        t.wv.clearHistory();
                    }
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    toast("Đã xoá dữ liệu");
                })
                .setNegativeButton("Huỷ", null)
                .show();
    }

    // ------------------------------------------------------------------ dấu trang

    JSONArray bookmarks() {
        try { return new JSONArray(prefs.getString("bm", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    void addBookmark() {
        String u = cur.wv.getUrl();
        if (u == null || u.startsWith(HOME)) { toast("Trang chủ không cần lưu"); return; }
        try {
            JSONArray a = bookmarks();
            for (int i = 0; i < a.length(); i++) {
                if (u.equals(a.getJSONObject(i).optString("u"))) { toast("Đã có trong dấu trang"); return; }
            }
            JSONObject o = new JSONObject();
            o.put("t", TextUtils.isEmpty(cur.title) ? u : cur.title);
            o.put("u", u);
            a.put(o);
            prefs.edit().putString("bm", a.toString()).apply();
            toast("Đã thêm vào dấu trang");
        } catch (Exception e) {
            toast("Lỗi lưu dấu trang");
        }
    }

    void showBookmarks() {
        final JSONArray a = bookmarks();
        if (a.length() == 0) { toast("Chưa có dấu trang nào"); return; }
        String[] names = new String[a.length()];
        for (int i = 0; i < a.length(); i++) names[i] = a.optJSONObject(i).optString("t");
        new AlertDialog.Builder(this)
                .setTitle("Dấu trang")
                .setItems(names, (d, w) -> {
                    final JSONObject o = a.optJSONObject(w);
                    new AlertDialog.Builder(this)
                            .setTitle(o.optString("t"))
                            .setItems(new String[]{"Mở", "Mở trong tab mới", "Xoá dấu trang"}, (d2, w2) -> {
                                if (w2 == 0) load(cur, o.optString("u"));
                                else if (w2 == 1) createTab(o.optString("u"), true, true);
                                else {
                                    a.remove(w);
                                    prefs.edit().putString("bm", a.toString()).apply();
                                    toast("Đã xoá");
                                }
                            })
                            .show();
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    // ------------------------------------------------------------------ tiện ích

    String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
