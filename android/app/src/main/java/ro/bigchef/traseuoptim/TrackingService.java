package ro.bigchef.traseuoptim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Serviciul de tura: urmareste GPS-ul cat timp agentul e "On Duty",
 * si cu ecranul blocat sau cu Waze deschis (notificare permanenta).
 * Punctele se pun intr-o coada pe telefon si se trimit la server la fiecare minut.
 */
public class TrackingService extends Service {

    static final String ACTIUNE_STOP = "ro.bigchef.traseuoptim.STOP";

    private static final String SERVER_URL =
            "https://script.google.com/macros/s/AKfycbwWNLktAcTya9DyfQSsj6TCcKOydvluAazASeDadCMALwdaDUPXW2Wjalo46MM60HI/exec";
    private static final String API_KEY = "bc2026x7k9m2";

    private static final String CANAL = "tura";
    private static final int ID_NOTIFICARE = 1;
    private static final long GPS_INTERVAL_MS = 20_000;      // cel mult un punct la 20 s
    private static final float GPS_DISTANTA_M = 30f;          // ... sau la 30 m parcursi
    private static final long TRIMITERE_MS = 60_000;          // trimitem coada la fiecare minut
    private static final long STATIONAR_MS = 5 * 60_000;      // pe loc: un punct la 5 minute
    private static final float PRECIZIE_MAXIMA_M = 150f;      // ignoram puncte foarte imprecise

    private static final Object BLOCARE_COADA = new Object();

    private LocationManager lm;
    private HandlerThread fir;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private Location ultima;
    private long ultimulPunctMs = 0;
    private boolean gpsAnuntatOprit = false;

    private final LocationListener ascultator = new LocationListener() {
        @Override public void onLocationChanged(Location l) { punctNou(l, "GPS"); }
        @Override public void onProviderDisabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) gpsOprit();
        }
        @Override public void onProviderEnabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) gpsRepornit();
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        fir = new HandlerThread("tura");
        fir.start();
        handler = new Handler(fir.getLooper());
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTIUNE_STOP.equals(intent.getAction())) {
            opresteTot();
            return START_NOT_STICKY;
        }
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (!p.getBoolean("in_tura", false)) {   // repornit de sistem dupa Off Duty
            opresteTot();
            return START_NOT_STICKY;
        }
        pornesteInPrimPlan("Tura activa - se inregistreaza traseul");
        pornesteGps();
        handler.removeCallbacks(ciclu);
        handler.postDelayed(ciclu, TRIMITERE_MS);
        return START_STICKY;
    }

    private void pornesteInPrimPlan(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel c = new NotificationChannel(CANAL, "Tura activa", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Arata ca urmarirea traseului e pornita");
            nm.createNotificationChannel(c);
        }
        Intent deschide = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, deschide,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("TraseuOptim")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_NOTIFICARE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(ID_NOTIFICARE, n);
        }
    }

    private void actualizeazaNotificarea(String text) {
        pornesteInPrimPlan(text);
    }

    private void pornesteGps() {
        try {
            lm.removeUpdates(ascultator);
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, GPS_DISTANTA_M,
                        ascultator, fir.getLooper());
            } else {
                gpsOprit();
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000, 100f,
                        ascultator, fir.getLooper());
            }
        } catch (SecurityException e) {
            actualizeazaNotificarea("Lipseste permisiunea de locatie - deschide aplicatia");
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traseuoptim:tura");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(14 * 60 * 60 * 1000L);   // cel mult 14 ore pe tura
    }

    private void punctNou(Location l, String tip) {
        if (l == null) return;
        if (l.hasAccuracy() && l.getAccuracy() > PRECIZIE_MAXIMA_M) return;
        // un punct de retea nu inlocuieste unul GPS proaspat si mai precis
        if (ultima != null && LocationManager.NETWORK_PROVIDER.equals(l.getProvider())
                && LocationManager.GPS_PROVIDER.equals(ultima.getProvider())
                && l.getTime() - ultima.getTime() < 120_000) return;
        ultima = l;
        ultimulPunctMs = System.currentTimeMillis();
        gpsAnuntatOprit = false;
        salveazaUltima(l);
        String nota = l.hasSpeed() ? Math.round(l.getSpeed() * 3.6) + " km/h" : "";
        adaugaInCoada(eveniment(tip, l, nota));
    }

    private void gpsOprit() {
        if (gpsAnuntatOprit) return;
        gpsAnuntatOprit = true;
        adaugaInCoada(eveniment("GPS_OFF", ultima, "GPS oprit pe telefon"));
        actualizeazaNotificarea("ATENTIE: GPS oprit - porneste locatia!");
    }

    private void gpsRepornit() {
        adaugaInCoada(eveniment("GPS_ON", ultima, "GPS repornit"));
        actualizeazaNotificarea("Tura activa - se inregistreaza traseul");
        pornesteGps();
    }

    /** La fiecare minut: punct "pe loc" daca nu s-a miscat, apoi trimitem coada. */
    private final Runnable ciclu = new Runnable() {
        @Override public void run() {
            long acum = System.currentTimeMillis();
            if (ultima != null && acum - ultimulPunctMs >= STATIONAR_MS) {
                ultimulPunctMs = acum;
                adaugaInCoada(eveniment("STATIONAR", ultima, "pe loc"));
            }
            trimiteCoada(getApplicationContext());
            handler.postDelayed(this, TRIMITERE_MS);
        }
    };

    private JSONObject eveniment(String tip, Location l, String nota) {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String agent = p.getString("agent", "");
        JSONObject o = new JSONObject();
        try {
            Date d = new Date();
            o.put("client_time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d));
            o.put("route", "AGENT " + agent);
            o.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d));
            o.put("seq", "");
            o.put("store_code", "");
            o.put("event_type", tip);
            o.put("lat", l != null ? l.getLatitude() : "");
            o.put("lon", l != null ? l.getLongitude() : "");
            o.put("accuracy_m", l != null && l.hasAccuracy() ? Math.round(l.getAccuracy()) : "");
            o.put("note", nota);
            o.put("device_info", "TraseuOptim " + agent);
        } catch (Exception ignored) { }
        return o;
    }

    private void salveazaUltima(Location l) {
        try {
            JSONObject o = new JSONObject();
            o.put("lat", l.getLatitude());
            o.put("lon", l.getLongitude());
            o.put("acc", l.hasAccuracy() ? Math.round(l.getAccuracy()) : 0);
            o.put("t", l.getTime());
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putString("ultima_poz", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ---------------- Coada pe telefon (un eveniment JSON pe linie) ----------------

    private static File fisierCoada(Context c) {
        return new File(c.getFilesDir(), "coada.jsonl");
    }

    private void adaugaInCoada(JSONObject ev) {
        synchronized (BLOCARE_COADA) {
            try (FileOutputStream f = new FileOutputStream(fisierCoada(this), true)) {
                f.write((ev.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }

    private static List<String> citesteCoada(Context c) {
        List<String> linii = new ArrayList<>();
        File f = fisierCoada(c);
        if (!f.exists()) return linii;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) if (!s.trim().isEmpty()) linii.add(s);
        } catch (Exception ignored) { }
        return linii;
    }

    static int numarInCoada(Context c) {
        synchronized (BLOCARE_COADA) {
            return citesteCoada(c).size();
        }
    }

    /** Trimite pana la 100 de evenimente o data; le sterge din coada doar daca serverul confirma. */
    static void trimiteCoada(Context c) {
        List<String> toate;
        synchronized (BLOCARE_COADA) {
            toate = citesteCoada(c);
        }
        int trimise = 0;
        while (trimise < toate.size()) {
            List<String> lot = toate.subList(trimise, Math.min(toate.size(), trimise + 100));
            if (!trimiteLot(lot)) break;
            trimise += lot.size();
        }
        if (trimise == 0) return;
        synchronized (BLOCARE_COADA) {
            // rescriem coada fara ce s-a trimis (pastram ce s-a adaugat intre timp)
            List<String> acum = citesteCoada(c);
            List<String> ramase = acum.subList(Math.min(trimise, acum.size()), acum.size());
            try (FileOutputStream f = new FileOutputStream(fisierCoada(c), false)) {
                for (String s : ramase) f.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }

    private static boolean trimiteLot(List<String> lot) {
        HttpURLConnection con = null;
        try {
            JSONArray ev = new JSONArray();
            for (String s : lot) ev.put(new JSONObject(s));
            JSONObject corp = new JSONObject();
            corp.put("action", "events_batch");
            corp.put("key", API_KEY);
            corp.put("events", ev);
            byte[] date = corp.toString().getBytes(StandardCharsets.UTF_8);

            con = (HttpURLConnection) new URL(SERVER_URL).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(20_000);
            con.setReadTimeout(30_000);
            con.setDoOutput(true);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
            try (OutputStream o = con.getOutputStream()) { o.write(date); }

            int cod = con.getResponseCode();
            // Apps Script raspunde cu redirect 302 catre rezultat
            if (cod == 301 || cod == 302 || cod == 303 || cod == 307) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                con = (HttpURLConnection) new URL(loc).openConnection();
                con.setConnectTimeout(20_000);
                con.setReadTimeout(30_000);
                cod = con.getResponseCode();
            }
            if (cod != 200) return false;
            StringBuilder sb = new StringBuilder();
            try (InputStream in = con.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString().contains("\"ok\":true");
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private void opresteTot() {
        try { lm.removeUpdates(ascultator); } catch (Exception ignored) { }
        if (handler != null) handler.removeCallbacks(ciclu);
        if (ultima != null) adaugaInCoada(eveniment("GPS_STOP", ultima, "Off Duty"));
        final Context c = getApplicationContext();
        if (handler != null) handler.post(() -> trimiteCoada(c));   // ultima trimitere
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { lm.removeUpdates(ascultator); } catch (Exception ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (fir != null) fir.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
