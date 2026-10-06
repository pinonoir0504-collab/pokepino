package com.tdwatch.stockwatchv4;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DisclosureCatchUpWorker extends Worker {
    private static final String API_BASE = "https://webapi.yanoshin.jp/webapi/tdnet/list";

    public DisclosureCatchUpWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static void enqueueNow(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(DisclosureCatchUpWorker.class)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                "pinowatch-disclosure-catchup-now-v116", ExistingWorkPolicy.REPLACE, work);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        try {
            if (PushRegistration.needsRefresh(context)) {
                String token = Tasks.await(FirebaseMessaging.getInstance().getToken(), 20, TimeUnit.SECONDS);
                PushRegistration.registerBlocking(context, token);
            }
        } catch (Exception ignored) {}

        if (!NotificationReliability.masterEnabled(context)) {
            NotificationReliability.recordCatchup(context, "通知OFF", 0);
            return Result.success();
        }

        try {
            List<String> urls = catchupUrls(context);
            Map<String, Item> unique = new LinkedHashMap<>();
            int failedRequests = 0;
            for (String url : urls) {
                JSONArray rows = fetchRows(url);
                if (rows == null) { failedRequests++; continue; }
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    JSONObject wrapped = row.optJSONObject("Tdnet");
                    if (wrapped != null) row = wrapped;
                    String code = normCode(row.optString("company_code", row.optString("companyCode", "")));
                    String company = row.optString("company_name", row.optString("companyName", "")).trim();
                    String title = row.optString("title", "").trim();
                    String urlValue = row.optString("document_url", row.optString("documentUrl", "")).trim();
                    if (code == null || title.isEmpty()) continue;
                    String label = (code + " " + company).trim();
                    Item item = new Item(label, title, urlValue);
                    String key = NotificationReliability.stableKey(urlValue, label, title);
                    unique.putIfAbsent(key, item);
                }
            }
            if (unique.isEmpty() && failedRequests == urls.size()) {
                NotificationReliability.recordCatchup(context, "全回収API失敗", 0);
                return Result.retry();
            }

            List<Item> items = new ArrayList<>(unique.values());
            List<String> keys = new ArrayList<>(unique.keySet());

            if (!NotificationReliability.catchupInitialized(context)) {
                NotificationReliability.seedSeen(context, keys);
                NotificationReliability.recordCatchup(context, "初回基準作成", items.size());
                return Result.success();
            }

            for (int i = items.size() - 1; i >= 0; i--) {
                Item item = items.get(i);
                String key = keys.get(i);
                if (NotificationReliability.matchesPreferences(context, item.companyLabel, item.title)) {
                    NotificationReliability.notifyDisclosure(
                            context, item.companyLabel, item.title, item.url, "catchup", false);
                } else {
                    NotificationReliability.markSeen(context, key);
                }
            }
            NotificationReliability.recordCatchup(context, "OK", items.size());
            return Result.success();
        } catch (Exception e) {
            NotificationReliability.recordCatchup(context, "通信/解析エラー", 0);
            return Result.retry();
        }
    }

    private static List<String> catchupUrls(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("push", Context.MODE_PRIVATE);
        boolean favoritesOnly = p.getBoolean("favoritesOnly", true);
        List<String> favorites = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("favorites", "[]"));
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < a.length() && favorites.size() < 100; i++) {
                String code = normCode(a.optString(i, ""));
                if (code != null && seen.add(code)) favorites.add(code);
            }
        } catch (Exception ignored) {}

        List<String> urls = new ArrayList<>();
        if (favoritesOnly && !favorites.isEmpty()) {
            for (int i = 0; i < favorites.size(); i += 25) {
                List<String> batch = favorites.subList(i, Math.min(favorites.size(), i + 25));
                urls.add(API_BASE + "/" + android.text.TextUtils.join("-", batch) + ".json2?limit=300");
            }
        } else {
            urls.add(API_BASE + "/recent.json2?limit=300");
        }
        return urls;
    }

    private static JSONArray fetchRows(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "PinoWatch/1.16 Android CatchUp");
            int status = c.getResponseCode();
            if (status < 200 || status >= 400) return null;
            String raw;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                raw = out.toString(StandardCharsets.UTF_8.name());
            }
            JSONObject root = new JSONObject(raw);
            JSONArray rows = root.optJSONArray("items");
            if (rows == null) rows = root.optJSONArray("Tdnet");
            return rows;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String normCode(String s) {
        String x = String.valueOf(s == null ? "" : s).trim().toUpperCase();
        if (x.matches("[0-9A-Z]{5}") && x.endsWith("0")) x = x.substring(0, 4);
        return x.matches("[0-9]{3}[0-9A-Z]") ? x : null;
    }

    private static final class Item {
        final String companyLabel, title, url;
        Item(String companyLabel, String title, String url) {
            this.companyLabel = companyLabel;
            this.title = title;
            this.url = url;
        }
    }
}
