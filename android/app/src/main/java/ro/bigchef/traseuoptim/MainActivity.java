package ro.bigchef.traseuoptim;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Ecranul aplicatiei: o pagina web (traseuoptim.html de pe GitHub Pages)
 * plus o punte JavaScript -> Android pentru pornirea / oprirea urmaririi GPS.
 */
public class MainActivity extends Activity {

    static final String URL_PAGINA = "https://operatiuni.github.io/trasee/traseuoptim.html";
    static final String PREFS = "traseuoptim";
    private static final int COD_PERMISIUNI = 7;

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " TraseuOptimApp/" + versiune());

        web.addJavascriptInterface(new Punte(), "TraseuOptim");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.endsWith("operatiuni.github.io")) return false;
                // Waze, Google Maps, telefon etc. se deschid in aplicatiile lor
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        cerePermisiuni();
        web.loadUrl(URL_PAGINA + "?app=" + versiune());
    }

    private String versiune() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void cerePermisiuni() {
        java.util.ArrayList<String> lipsa = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            lipsa.add(Manifest.permission.ACCESS_FINE_LOCATION);
            lipsa.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            lipsa.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!lipsa.isEmpty()) requestPermissions(lipsa.toArray(new String[0]), COD_PERMISIUNI);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        web.evaluateJavascript("window.dupaPermisiuni && window.dupaPermisiuni()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.laRevenire && window.laRevenire()", null);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);   // nu inchidem aplicatia; serviciul GPS ramane pornit
    }

    /** Functii apelate din pagina: window.TraseuOptim.xxx() */
    private class Punte {

        @JavascriptInterface
        public boolean esteInTura() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("in_tura", false);
        }

        @JavascriptInterface
        public boolean areLocatie() {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public boolean gpsPornit() {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        }

        @JavascriptInterface
        public void cerePermisiuniDinNou() {
            runOnUiThread(MainActivity.this::cerePermisiuni);
        }

        @JavascriptInterface
        public void deschideSetariLocatie() {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }

        @JavascriptInterface
        public boolean bateriaLiberata() {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void elibereazaBateria() {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }

        /** Porneste urmarirea GPS (On Duty). */
        @JavascriptInterface
        public boolean incepeTura(String agent, String zona) {
            if (!areLocatie()) return false;
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putBoolean("in_tura", true);
            e.putString("agent", agent == null ? "" : agent);
            e.putString("zona", zona == null ? "" : zona);
            e.putLong("start_ms", System.currentTimeMillis());
            e.apply();
            Intent i = new Intent(MainActivity.this, TrackingService.class);
            startForegroundService(i);
            return true;
        }

        /** Opreste urmarirea GPS (Off Duty). */
        @JavascriptInterface
        public void incheieTura() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("in_tura", false).apply();
            Intent i = new Intent(MainActivity.this, TrackingService.class);
            i.setAction(TrackingService.ACTIUNE_STOP);
            startService(i);
        }

        /** Ultima pozitie stiuta de serviciu, ca JSON: {lat,lon,acc,t} sau "" */
        @JavascriptInterface
        public String ultimaPozitie() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("ultima_poz", "");
        }

        /** Cate puncte GPS asteapta sa fie trimise. */
        @JavascriptInterface
        public int puncteNetrimise() {
            return TrackingService.numarInCoada(MainActivity.this);
        }

        @JavascriptInterface
        public String versiuneAplicatie() {
            return versiune();
        }
    }
}
