package dev.jev.wechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Jev 配置：保存在微信进程自己的 SharedPreferences 里，因此 Hook 代码与设置对话框都能直接读写。
 *
 * 配置 JSON：
 * {"apiUrl":"https://openrouter.ai/api/v1/chat/completions","apiKey":"...","model":"..."}
 *
 * apiUrl 兼容 OpenAI Chat Completions 协议（OpenRouter / DeepSeek 官方 / 其他中转均可）。
 */
public final class JevConfig {
    private static final String PREFS = "jev_prefs";
    private static final String KEY = "config_json";

    public static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    public final String apiUrl;
    public final String apiKey;
    public final String model;

    private JevConfig(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public static JevConfig load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY, null);
            if (raw == null || raw.trim().isEmpty()) return null;
            return parse(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 返回错误信息；null 表示保存成功。 */
    public static String save(Context ctx, String rawJson) {
        JevConfig cfg;
        try {
            cfg = parse(rawJson);
        } catch (Throwable t) {
            return "JSON 无法解析：" + t.getMessage();
        }
        if (cfg == null) return "缺少 apiKey 或 model";
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, rawJson.trim()).apply();
            return null;
        } catch (Throwable t) {
            return "保存失败：" + t.getMessage();
        }
    }

    private static JevConfig parse(String raw) throws Exception {
        if (raw == null) return null;
        String s = raw.trim();
        int b = s.indexOf('{');
        int e = s.lastIndexOf('}');
        if (b < 0 || e <= b) return null;
        JSONObject o = new JSONObject(s.substring(b, e + 1));
        String key = o.optString("apiKey", "").trim();
        String model = o.optString("model", "").trim();
        String url = o.optString("apiUrl", "").trim();
        if (key.isEmpty() || model.isEmpty()) return null;
        if (url.isEmpty()) url = DEFAULT_API_URL;
        if (!key.startsWith("在这里")) return new JevConfig(url, key, model);
        return null;
    }

    /** 长按菜单「Jev设置」入口：弹出对话框编辑配置 JSON。 */
    public static void showSettingsDialog(View anchor) {
        try {
            Context base = anchor.getContext();
            Context themed = new ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);

            LinearLayout box = new LinearLayout(themed);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(themed, 20);
            box.setPadding(pad, dp(themed, 8), pad, 0);

            EditText input = new EditText(themed);
            input.setTextSize(13f);
            input.setTypeface(Typeface.MONOSPACE);
            input.setSingleLine(false);
            input.setMinLines(5);
            JevConfig cur = load(base);
            String prefill = cur != null
                    ? "{\"apiUrl\":\"" + cur.apiUrl + "\",\n\"apiKey\":\"" + cur.apiKey + "\",\n\"model\":\"" + cur.model + "\"}"
                    : "{\n  \"apiUrl\": \"" + DEFAULT_API_URL + "\",\n  \"apiKey\": \"在这里粘贴你的 DeepSeek API Key\",\n  \"model\": \"" + DEFAULT_MODEL + "\"\n}";
            input.setText(prefill);
            box.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView hint = new TextView(themed);
            hint.setTextSize(11f);
            hint.setTextColor(0xFF999999);
            hint.setText("支持任意 OpenAI 兼容接口（DeepSeek / OpenRouter / 中转），例如：\n"
                    + "{\"apiUrl\":\"https://openrouter.ai/api/v1/chat/completions\",\n"
                    + "  \"apiKey\":\"sk-or-...\",\n"
                    + "  \"model\":\"deepseek/deepseek-chat\"}");
            hint.setPadding(0, dp(themed, 12), 0, 0);
            box.addView(hint);

            ScrollView sv = new ScrollView(themed);
            sv.addView(box);

            new AlertDialog.Builder(themed)
                    .setTitle("Jev 设置")
                    .setView(sv)
                    .setPositiveButton("保存", (d, w) -> {
                        String err = save(base, input.getText().toString());
                        Toast.makeText(base, err == null
                                ? "Jev 配置已保存 ✅" : "Jev 配置出错：" + err, Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            JevLog.e("settings dialog failed", t);
        }
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static boolean isActivity(Context c) {
        return c instanceof Activity;
    }
}
