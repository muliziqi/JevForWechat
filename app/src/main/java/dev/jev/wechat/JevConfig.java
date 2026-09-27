package dev.jev.wechat;

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
 * Jev 配置：保存在微信进程自己的 SharedPreferences 里，Hook 代码与设置对话框都能直接读写。
 *
 * 双模型链路（推荐）——Jev（OpenRouter）负责决策分析，DeepSeek 负责起草回复：
 * {
 *   "analysis": {"apiUrl":"https://openrouter.ai/api/v1/chat/completions","apiKey":"sk-or-...","model":"typesafe/jev-router"},
 *   "reply":    {"apiUrl":"https://api.deepseek.com/chat/completions","apiKey":"sk-...","model":"deepseek-chat"}
 * }
 *
 * 也支持平铺格式（两个环节共用同一个接口）：
 * {"apiUrl":"https://openrouter.ai/api/v1/chat/completions","apiKey":"sk-or-...","model":"typesafe/jev-router"}
 *
 * 所有接口均为 OpenAI Chat Completions 兼容协议。
 */
public final class JevConfig {
    private static final String PREFS = "jev_prefs";
    private static final String KEY = "config_json";

    public static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    public static final String JEV_MODEL = "typesafe/jev-router";
    public static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
    public static final String DEEPSEEK_MODEL = "deepseek-chat";

    /** 一个可调用的 OpenAI 兼容端点。 */
    public static final class Endpoint {
        public final String apiUrl;
        public final String apiKey;
        public final String model;

        Endpoint(String apiUrl, String apiKey, String model) {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.model = model;
        }

        boolean valid() {
            return apiKey != null && !apiKey.isEmpty() && model != null && !model.isEmpty();
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("apiUrl", apiUrl == null ? "" : apiUrl);
                o.put("apiKey", apiKey == null ? "" : apiKey);
                o.put("model", model == null ? "" : model);
            } catch (Throwable ignored) {
            }
            return o;
        }
    }

    public final Endpoint analysis; // Jev 决策分析
    public final Endpoint reply;    // DeepSeek 建议回复；null = 不起草回复

    private JevConfig(Endpoint analysis, Endpoint reply) {
        this.analysis = analysis;
        this.reply = reply;
    }

    boolean hasReply() {
        return reply != null && reply.valid();
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
        if (cfg == null) return "缺少 analysis 的 apiKey 或 model";
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

        if (o.has("analysis") || o.has("reply")) {
            Endpoint analysis = endpoint(o.optJSONObject("analysis"));
            if (analysis == null) return null;
            Endpoint reply = endpoint(o.optJSONObject("reply"));
            return new JevConfig(analysis, reply);
        }
        // 平铺：两个环节共用
        Endpoint flat = endpoint(o);
        if (flat == null) return null;
        return new JevConfig(flat, flat);
    }

    private static Endpoint endpoint(JSONObject o) {
        if (o == null) return null;
        String key = o.optString("apiKey", "").trim();
        String model = o.optString("model", "").trim();
        String url = o.optString("apiUrl", "").trim();
        if (key.isEmpty() || model.isEmpty() || key.startsWith("在这里") || key.contains("你的")) return null;
        return new Endpoint(url.isEmpty() ? DEEPSEEK_URL : url, key, model);
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
            input.setMinLines(6);
            JevConfig cur = load(base);
            input.setText(cur != null ? preset(cur.analysis, cur.reply) : preset(null, null));
            box.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView hint = new TextView(themed);
            hint.setTextSize(11f);
            hint.setTextColor(0xFF999999);
            hint.setText("analysis = Jev 决策分析（OpenRouter），reply = DeepSeek 起草回复（可留空此段）。\n"
                    + "不想用两个 Key？把 reply 的 apiUrl 也填 OpenRouter，model 填 deepseek/deepseek-chat，apiKey 同 analysis。");
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

    private static String preset(Endpoint analysis, Endpoint reply) {
        JSONObject o = new JSONObject();
        try {
            o.put("analysis", analysis != null ? analysis.toJson()
                    : new JSONObject()
                    .put("apiUrl", OPENROUTER_URL)
                    .put("apiKey", "在这里粘贴你的 OpenRouter Key")
                    .put("model", JEV_MODEL));
            o.put("reply", reply != null ? reply.toJson()
                    : new JSONObject()
                    .put("apiUrl", DEEPSEEK_URL)
                    .put("apiKey", "在这里粘贴你的 DeepSeek Key（不需要回复功能可整段删掉）")
                    .put("model", DEEPSEEK_MODEL));
            return o.toString(2);
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
