package tv.clean.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int TOP = -1, DOCK = 0;
    private static final float FOCUS_SCALE = 1.08f;

    static final class App {
        String pkg, label;
        ComponentName cn;
        Drawable banner, icon;
        int color;
    }

    private final List<App> dock = new ArrayList<>(), grid = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Map<String, Typeface> fonts = new HashMap<>();
    private final Map<Integer, ComponentName> keyActions = new HashMap<>();

    private int cols, tileW, tileH, gap, bandPad;
    private float dp;
    private ScrollView scroller;
    private FrameLayout page, hero;
    private LinearLayout dockBand, menu;
    private RecyclerView gridView;
    private HighlightView highlight;
    private TextView greeting, dockTitle, pillWifi, stats;
    private TextView[] pills;
    private View glow;
    private GradientDrawable glowDrawable;
    private int glowColor = 0xFF5A6CFF;
    private ValueAnimator glowAnim;
    private ObjectAnimator scrollAnim;

    // Focus model: row TOP is the pill bar, DOCK the dock, 1..n the grid rows.
    private int row = DOCK, col = 0, lastPill = 0, lastDockCol = 0;
    private View focusedCard;
    private TextView focusedName;
    private boolean resumed, menuOpen, loaded;
    private int menuIndex;
    private final List<TextView> menuItems = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private SharedPreferences prefs;
    private ConnectivityManager.NetworkCallback netCallback;
    private long[] lastCpu;
    private boolean cpuDenied;
    private String tempPath;
    private boolean tempSearched;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        cols = Math.max(1, getResources().getInteger(R.integer.columns));
        for (String s : getResources().getStringArray(R.array.key_actions)) {
            int eq = s.indexOf('=');
            if (eq > 0) keyActions.put(Integer.parseInt(s.substring(0, eq).trim()), ComponentName.unflattenFromString(s.substring(eq + 1).trim()));
        }

        scroller = findViewById(R.id.scroller);
        page = findViewById(R.id.page);
        hero = findViewById(R.id.hero);
        dockBand = findViewById(R.id.dock);
        gridView = findViewById(R.id.grid);
        highlight = findViewById(R.id.highlight);
        greeting = findViewById(R.id.greeting);
        dockTitle = findViewById(R.id.dock_title);
        pillWifi = findViewById(R.id.pill_wifi);
        glow = findViewById(R.id.glow);
        menu = findViewById(R.id.inputs_menu);
        stats = findViewById(R.id.stats);
        pills = new TextView[]{findViewById(R.id.pill_inputs), pillWifi, findViewById(R.id.pill_settings)};

        greeting.setTypeface(font(300));
        ((TextView) findViewById(R.id.clock)).setTypeface(font(700));
        ((TextView) findViewById(R.id.date)).setTypeface(font(700));
        dockTitle.setTypeface(font(300));
        for (TextView p : pills) p.setTypeface(font(400));
        stats.setTypeface(font(400));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        gap = Math.round(28 * dp);
        bandPad = Math.round(22 * dp);
        int avail = screenW - Math.round(96 * dp) - 2 * bandPad - (cols - 1) * gap;
        tileW = Math.min(Math.round(184 * dp), avail / cols);
        tileH = Math.round(tileW * 9f / 16f);

        hero.getLayoutParams().height = screenH;
        dockBand.setPadding(bandPad, Math.round(8 * dp), bandPad, Math.round(12 * dp));
        int gridPadX = Math.max(0, bandPad - gap / 2);
        gridView.setPadding(gridPadX, Math.round(8 * dp), gridPadX, Math.round(12 * dp));
        gridView.setClipToPadding(false);
        gridView.setBackgroundResource(R.drawable.dock_band);
        // Dock title sits above the band, left-aligned with the first dock tile.
        int bandW = cols * tileW + (cols - 1) * gap + 2 * bandPad;
        int bandH = Math.round(8 * dp) + Math.round(10 * dp) + tileH + Math.round(6 * dp) + Math.round(12 * dp);
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) dockTitle.getLayoutParams();
        tp.leftMargin = (screenW - bandW) / 2 + bandPad;
        tp.bottomMargin = Math.round(22 * dp) + bandH + Math.round(14 * dp);
        // Glow: a square radial gradient that fades out exactly at its edge, stretched
        // into a wide ellipse centred on the dock title.
        int glowSize = Math.round(320 * dp);
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) glow.getLayoutParams();
        gp.width = glowSize;
        gp.height = glowSize;
        gp.leftMargin = tp.leftMargin + Math.round(110 * dp) - glowSize / 2;
        gp.bottomMargin = tp.bottomMargin + Math.round(26 * dp) - glowSize / 2;
        glow.setScaleX(2.6f);
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(glowSize / 2f);
        glow.setBackground(glowDrawable);
        findViewById(R.id.tail).getLayoutParams().height = screenH / 2;

        // Each grid cell is tile + gap wide, so the tiles line up under the dock tiles.
        gridView.getLayoutParams().width = cols * (tileW + gap);
        gridView.setLayoutManager(new GridLayoutManager(this, cols));
        gridView.setItemAnimator(null);
        gridView.setAdapter(new GridAdapter());

        pills[0].setOnClickListener(v -> openMenu());
        restoreFocus();
        loadApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { ui.post(MainActivity.this::updateWifi); }
            @Override public void onLost(Network n) { ui.post(MainActivity.this::updateWifi); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { ui.post(MainActivity.this::updateWifi); }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        bg.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
        updateGreeting();
        updateWifi();
        if (getResources().getBoolean(R.bool.show_stats)) ui.post(statsTick);
        page.post(() -> applyFocus(false));
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(statsTick);
        unregisterReceiver(tickReceiver);
        saveFocus();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on Home: back to the dock. From inside an app the
        // activity is not resumed yet, so the remembered tile stays focused.
        if (resumed && Intent.ACTION_MAIN.equals(intent.getAction())) {
            closeMenu();
            row = DOCK;
            col = Math.min(lastDockCol, Math.max(0, dock.size() - 1));
            applyFocus(true);
        }
    }

    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateGreeting(); }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        final String keepPkg = focusedPackage();
        bg.execute(() -> {
            PackageManager pm = getPackageManager();
            Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
            hidden.add(getPackageName());
            Map<String, String> labels = new HashMap<>();
            for (String s : getResources().getStringArray(R.array.label_overrides)) {
                int eq = s.indexOf('=');
                if (eq > 0) labels.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
            }
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            Map<String, App> byPkg = new HashMap<>();
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
                App a = new App();
                a.pkg = ai.packageName;
                a.cn = new ComponentName(ai.packageName, ai.name);
                String o = labels.get(a.pkg);
                a.label = o != null ? o : String.valueOf(ri.loadLabel(pm));
                a.banner = ai.loadBanner(pm);
                if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
                if (a.banner == null) a.icon = ri.loadIcon(pm);
                a.color = sampleColor(a.banner != null ? a.banner : a.icon);
                byPkg.put(a.pkg, a);
            }
            List<App> d = new ArrayList<>();
            for (String p : getResources().getStringArray(R.array.dock_apps)) {
                App a = byPkg.remove(p.trim());
                if (a != null && d.size() < cols) d.add(a);
            }
            List<App> g = new ArrayList<>(byPkg.values());
            Collections.sort(g, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> showApps(d, g, keepPkg));
        });
    }

    private void showApps(List<App> d, List<App> g, String keepPkg) {
        if (isDestroyed()) return;
        dock.clear();
        dock.addAll(d);
        grid.clear();
        grid.addAll(g);
        dockBand.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < dock.size(); i++) {
            View t = inf.inflate(R.layout.tile, dockBand, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = gap;
            t.setLayoutParams(lp);
            bindTile(t, dock.get(i));
            // Dock apps show their name in large type above the band instead.
            t.findViewById(R.id.name).setVisibility(View.GONE);
            dockBand.addView(t);
        }
        gridView.getAdapter().notifyDataSetChanged();
        loaded = true;
        if (keepPkg != null) focusPackage(keepPkg);
        clampFocus();
        focusedCard = null;
        focusedName = null;
        page.post(() -> applyFocus(false));
    }

    private void bindTile(View t, App a) {
        View card = t.findViewById(R.id.card);
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = tileW;
        lp.height = tileH;
        card.setLayoutParams(lp);
        card.setClipToOutline(true);
        card.setScaleX(1f);
        card.setScaleY(1f);
        ImageView iv = t.findViewById(R.id.banner);
        if (a.banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(0, 0, 0, 0);
            iv.setImageDrawable(a.banner);
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = Math.round(tileH * 0.2f);
            iv.setPadding(p, p, p, p);
            iv.setImageDrawable(a.icon);
        }
        TextView name = t.findViewById(R.id.name);
        name.setText(a.label);
        name.setTypeface(font(400));
        name.setMaxWidth(tileW + gap);
        name.setAlpha(0f);
    }

    /** Dominant saturated colour of a banner, for the ambient glow behind the dock title. */
    private static int sampleColor(Drawable dr) {
        if (dr == null) return 0xFF5A6CFF;
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        dr.setBounds(0, 0, 32, 18);
        dr.draw(c);
        float[] weight = new float[12];
        float[][] rgb = new float[12][3];
        float[] hsv = new float[3];
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                Color.colorToHSV(px, hsv);
                float w = hsv[1] * hsv[2];
                if (w < 0.15f) continue;
                int k = Math.min(11, (int) (hsv[0] / 30f));
                weight[k] += w;
                rgb[k][0] += Color.red(px) * w;
                rgb[k][1] += Color.green(px) * w;
                rgb[k][2] += Color.blue(px) * w;
            }
        }
        b.recycle();
        int best = -1;
        for (int k = 0; k < 12; k++) if (best < 0 || weight[k] > weight[best]) best = k;
        if (best < 0 || weight[best] < 1f) return 0xFF8A93B0;
        int col = Color.rgb((int) (rgb[best][0] / weight[best]), (int) (rgb[best][1] / weight[best]),
                (int) (rgb[best][2] / weight[best]));
        Color.colorToHSV(col, hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    // ---------------------------------------------------------------- focus

    private int gridRows() {
        return (grid.size() + cols - 1) / cols;
    }

    private int rowSize(int r) {
        if (r == TOP) return pills.length;
        if (r == DOCK) return dock.size();
        return Math.min(cols, grid.size() - (r - 1) * cols);
    }

    private void clampFocus() {
        if (row > gridRows()) row = gridRows();
        if (row == DOCK && dock.isEmpty()) row = grid.isEmpty() ? TOP : 1;
        col = Math.max(0, Math.min(col, rowSize(row) - 1));
    }

    private App focusedApp() {
        if (row == DOCK && col < dock.size()) return dock.get(col);
        if (row >= 1) {
            int i = (row - 1) * cols + col;
            if (i < grid.size()) return grid.get(i);
        }
        return null;
    }

    private String focusedPackage() {
        App a = focusedApp();
        return a != null ? a.pkg : prefs.getString("pkg", null);
    }

    private void focusPackage(String pkg) {
        for (int i = 0; i < dock.size(); i++) {
            if (dock.get(i).pkg.equals(pkg)) { row = DOCK; col = i; return; }
        }
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i).pkg.equals(pkg)) { row = 1 + i / cols; col = i % cols; return; }
        }
    }

    private void saveFocus() {
        prefs.edit().putInt("row", row).putInt("col", col).putInt("pill", lastPill)
                .putInt("dockCol", lastDockCol).putString("pkg", focusedPackage()).apply();
    }

    private void restoreFocus() {
        row = prefs.getInt("row", DOCK);
        col = prefs.getInt("col", 0);
        lastPill = prefs.getInt("pill", 0);
        lastDockCol = prefs.getInt("dockCol", 0);
        if (row == TOP) row = DOCK;
    }

    private View tileView(int r, int c) {
        if (r == TOP) return pills[c];
        if (r == DOCK) return c < dockBand.getChildCount() ? dockBand.getChildAt(c) : null;
        RecyclerView.LayoutManager lm = gridView.getLayoutManager();
        return lm == null ? null : lm.findViewByPosition((r - 1) * cols + c);
    }

    private void applyFocus(boolean animate) {
        if (!loaded) return;
        clampFocus();
        View tile = tileView(row, col);
        if (tile == null) return;
        View card = row == TOP ? tile : tile.findViewById(R.id.card);
        TextView name = row == TOP ? null : tile.findViewById(R.id.name);
        if (focusedCard != null && focusedCard != card) {
            if (focusedCard instanceof TextView) ((TextView) focusedCard).setTextColor(0xD9FFFFFF);
            else focusedCard.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        }
        if (focusedName != null && focusedName != name) focusedName.animate().alpha(0f).setDuration(120).start();
        focusedCard = card;
        focusedName = name;
        if (row == TOP) {
            ((TextView) card).setTextColor(0xFFFFFFFF);
            lastPill = col;
        } else {
            card.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(animate ? 200 : 0).start();
            if (name != null) name.animate().alpha(1f).setDuration(animate ? 200 : 0).start();
        }
        if (row == DOCK) {
            lastDockCol = col;
            App a = dock.get(col);
            dockTitle.setText(a.label);
            setGlow(a.color, animate);
        }
        dockTitle.animate().alpha(row == DOCK ? 1f : 0.35f).setDuration(200).start();
        glow.animate().alpha(row == DOCK ? 1f : 0.3f).setDuration(300).start();

        RectF r = rectIn(card, page);
        float radius;
        if (row == TOP) {
            radius = r.height() / 2f;
        } else {
            float dx = r.width() * (FOCUS_SCALE - 1f) / 2f, dy = r.height() * (FOCUS_SCALE - 1f) / 2f;
            r.inset(-dx, -dy);
            radius = 12 * dp * FOCUS_SCALE;
        }
        highlight.moveTo(r, radius, animate);
        scrollTo(row <= DOCK ? 0 : Math.max(0, (int) (gridView.getTop() + tile.getTop() - 70 * dp)), animate);
    }

    private static RectF rectIn(View v, View ancestor) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null && cur != ancestor) {
            x += cur.getLeft() - cur.getScrollX();
            y += cur.getTop() - cur.getScrollY();
            cur = (View) cur.getParent();
        }
        return new RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private void scrollTo(int y, boolean animate) {
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            scroller.scrollTo(0, y);
            return;
        }
        scrollAnim = ObjectAnimator.ofInt(scroller, "scrollY", scroller.getScrollY(), y);
        scrollAnim.setDuration(380);
        scrollAnim.setInterpolator(new DecelerateInterpolator(2f));
        scrollAnim.start();
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) {
            glowColor = color;
            paintGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, color);
        glowAnim.setDuration(400);
        glowAnim.addUpdateListener(v -> {
            glowColor = (int) v.getAnimatedValue();
            paintGlow(glowColor);
        });
        glowAnim.start();
    }

    private void paintGlow(int c) {
        glowDrawable.setColors(new int[]{(c & 0x00FFFFFF) | 0x47000000, (c & 0x00FFFFFF) | 0x14000000, 0});
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        ComponentName target = keyActions.get(k);
        if (target != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) start(new Intent().setComponent(target));
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (menuOpen) closeMenu(); else openMenu();
            }
            return true;
        }
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (menuOpen) {
            menuKey(k);
            return true;
        }
        if (dock.isEmpty() && grid.isEmpty() && row != TOP) row = TOP;
        int r = row, c = col;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == DOCK || (row == 1 && dock.isEmpty())) { r = TOP; c = lastPill; }
                else if (row == 1) { r = DOCK; c = Math.min(col, dock.size() - 1); }
                else if (row > 1) { r = row - 1; }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row == TOP) {
                    if (!dock.isEmpty()) { r = DOCK; c = Math.min(lastDockCol, dock.size() - 1); }
                    else if (!grid.isEmpty()) { r = 1; c = 0; }
                } else if (row < gridRows()) {
                    r = row + 1;
                    c = Math.min(col, rowSize(r) - 1);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) c = col - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < rowSize(row) - 1) c = col + 1;
                break;
            case KeyEvent.KEYCODE_BACK:
                if (row >= 1 || row == TOP) {
                    if (!dock.isEmpty()) { r = DOCK; c = Math.min(lastDockCol, dock.size() - 1); }
                }
                break;
            default:
                if (e.getRepeatCount() == 0) activate();
                return true;
        }
        if (r != row || c != col) {
            row = r;
            col = c;
            applyFocus(true);
        }
        return true;
    }

    private void activate() {
        if (row == TOP) {
            if (col == 0) openMenu();
            else if (col == 1) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else start(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
        App a = focusedApp();
        if (a == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i);
    }

    private boolean start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            return false;
        }
    }

    // ---------------------------------------------------------------- inputs menu

    private void openMenu() {
        menu.removeAllViews();
        menuItems.clear();
        menuActions.clear();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = tim != null ? tim.getTvInputList() : Collections.emptyList();
        List<TvInputInfo> sorted = new ArrayList<>(inputs);
        // HDMI ports first, then AV and the rest, each group by name.
        Collections.sort(sorted, (x, y) -> {
            int hx = x.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1, hy = y.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
            return hx != hy ? hx - hy : String.valueOf(label(x)).compareToIgnoreCase(String.valueOf(label(y)));
        });
        for (TvInputInfo info : sorted) {
            if (info.isHidden(this) || info.getType() == TvInputInfo.TYPE_TUNER) continue;
            final String id = info.getId();
            addMenuItem(String.valueOf(label(info)), () -> {
                Intent v = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
                if (!start(v)) openLiveTv();
            });
        }
        addMenuItem(getString(R.string.live_tv), this::openLiveTv);

        RectF r = rectIn(pills[0], page);
        menu.setTranslationX(r.left);
        menu.setTranslationY(r.bottom - scroller.getScrollY() + 10 * dp);
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setScaleY(0.92f);
        menu.setPivotY(0f);
        menu.animate().alpha(1f).scaleY(1f).setDuration(160).start();
        menuOpen = true;
        menuIndex = 0;
        paintMenu();
    }

    private CharSequence label(TvInputInfo info) {
        CharSequence c = info.loadCustomLabel(this);
        return c != null && c.length() > 0 ? c : info.loadLabel(this);
    }

    private void addMenuItem(String text, Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(font(400));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(0xE6FFFFFF);
        t.setSingleLine(true);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), 0);
        menu.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * dp)));
        menuItems.add(t);
        menuActions.add(action);
    }

    private void paintMenu() {
        for (int i = 0; i < menuItems.size(); i++) {
            TextView t = menuItems.get(i);
            t.setBackgroundResource(i == menuIndex ? R.drawable.menu_item_focused : 0);
            t.setTextColor(i == menuIndex ? 0xFFFFFFFF : 0xBFFFFFFF);
        }
    }

    private void menuKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (menuIndex > 0) { menuIndex--; paintMenu(); }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (menuIndex < menuItems.size() - 1) { menuIndex++; paintMenu(); }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                Runnable a = menuActions.get(menuIndex);
                closeMenu();
                a.run();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                closeMenu();
                break;
            default:
                break;
        }
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        menu.setVisibility(View.GONE);
    }

    private void openLiveTv() {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("com.tcl.tv");
        if (i == null) i = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
        start(i);
    }

    // ---------------------------------------------------------------- top bar

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening");
    }

    private void updateWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        String text;
        if (nc == null) {
            text = "Offline";
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = "Ethernet";
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiInfo wi = getSystemService(WifiManager.class).getConnectionInfo();
            String ssid = wi != null ? wi.getSSID() : null;
            if (ssid == null || ssid.contains("unknown ssid")) text = "Wi-Fi";
            else text = ssid.replaceAll("^\"|\"$", "");
        } else {
            text = "Connected";
        }
        if (!text.contentEquals(pillWifi.getText())) {
            pillWifi.setText(text);
            if (row == TOP) page.post(() -> applyFocus(true));
        }
    }

    // ---------------------------------------------------------------- stats

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            bg.execute(() -> {
                String text = readStats();
                ui.post(() -> {
                    if (text.isEmpty()) {
                        stats.setVisibility(View.GONE);
                    } else {
                        stats.setText(text);
                        stats.setVisibility(View.VISIBLE);
                    }
                });
            });
            ui.postDelayed(this, 3000);
        }
    };

    private String readStats() {
        StringBuilder sb = new StringBuilder();
        // Load where the TV allows it; otherwise the ondemand clock, which rises with load.
        String cpu = cpuLoad();
        if (cpu == null) cpu = cpuFreq();
        if (cpu != null) sb.append("CPU ").append(cpu);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        if (mi.totalMem > 0) {
            if (sb.length() > 0) sb.append("   ·   ");
            double gb = 1024.0 * 1024 * 1024;
            sb.append(String.format(Locale.US, "RAM %.1f / %.1f GB", (mi.totalMem - mi.availMem) / gb, mi.totalMem / gb));
        }
        String temp = cpuTemp();
        if (temp != null) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(temp);
        }
        return sb.toString();
    }

    /** System-wide CPU load from /proc/stat; null where SELinux hides it from apps. */
    private String cpuLoad() {
        if (cpuDenied) return null;
        String line = readLine("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) {
            cpuDenied = true;
            return null;
        }
        String[] f = line.trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 1; i < f.length && i <= 8; i++) {
            long v = Long.parseLong(f[i]);
            total += v;
            if (i == 4 || i == 5) idle += v;
        }
        long[] prev = lastCpu;
        lastCpu = new long[]{total, idle};
        if (prev == null || total <= prev[0]) return "–";
        long dt = total - prev[0], di = idle - prev[1];
        return Math.round(100.0 * (dt - di) / dt) + "%";
    }

    /** Highest current clock across online cores, e.g. "1.35 GHz". */
    private String cpuFreq() {
        long best = 0;
        for (int i = 0; i < 8; i++) {
            String v = readLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (v == null) continue;
            try {
                best = Math.max(best, Long.parseLong(v.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return best > 0 ? String.format(Locale.US, "%.2f GHz", best / 1e6) : null;
    }

    /** CPU temperature from the first CPU/SoC thermal zone the app may read. */
    private String cpuTemp() {
        if (!tempSearched) {
            tempSearched = true;
            String fallback = null;
            for (int i = 0; i < 12; i++) {
                String base = "/sys/class/thermal/thermal_zone" + i;
                String type = readLine(base + "/type");
                if (type == null || readLine(base + "/temp") == null) continue;
                if (fallback == null) fallback = base + "/temp";
                String t = type.toLowerCase(Locale.US);
                if (t.contains("cpu") || t.contains("soc")) {
                    tempPath = base + "/temp";
                    break;
                }
            }
            if (tempPath == null) tempPath = fallback;
        }
        if (tempPath == null) return null;
        String v = readLine(tempPath);
        if (v == null) return null;
        try {
            double c = Double.parseDouble(v.trim());
            if (c > 1000) c /= 1000;
            return c > 0 && c < 150 ? Math.round(c) + "°C" : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Exception ex) {
            return null;
        }
    }

    private Typeface font(int weight) {
        String key = String.valueOf(weight);
        Typeface t = fonts.get(key);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/Outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight).build();
            } catch (RuntimeException ex) {
                t = Typeface.create("sans-serif-light", Typeface.NORMAL);
            }
            fonts.put(key, t);
        }
        return t;
    }

    // ---------------------------------------------------------------- grid

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View t = LayoutInflater.from(parent.getContext()).inflate(R.layout.tile, parent, false);
            t.setLayoutParams(new RecyclerView.LayoutParams(tileW + gap, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(t) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            bindTile(h.itemView, grid.get(pos));
        }

        @Override
        public int getItemCount() {
            return grid.size();
        }
    }
}
