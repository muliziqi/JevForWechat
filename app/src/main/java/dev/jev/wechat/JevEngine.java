package dev.jev.wechat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Jev 决策引擎：把最近聊天记录发给 OpenAI Chat Completions 兼容接口（OpenRouter / DeepSeek / 其他），
 * 要求模型只输出一个 JSON，再解析成卡片文本。
 *
 * 分析结果以消息全文为键缓存；同一时刻同一条消息只发一次请求。
 */
final class JevEngine {

    static final class Result {
        final String text;      // 渲染好的卡片文本；失败时为 null
        final String error;     // 失败原因；成功时为 null

        Result(String text, String error) {
            this.text = text;
            this.error = error;
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "JevEngine");
        t.setDaemon(true);
        return t;
    });

    /** 消息全文 -> Result。LinkedHashMap 由主线程访问，requestInFlight 由多线程访问。 */
    private static final Map<String, Result> CACHE = new LinkedHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final int CACHE_MAX = 64;

    private JevEngine() {
    }

    enum State {DONE, RUNNING, MISSING}

    /** 查询某条消息当前的分析状态（主线程调用）。 */
    static State stateOf(String messageText) {
        synchronized (CACHE) {
            if (CACHE.containsKey(messageText)) return State.DONE;
        }
        if (IN_FLIGHT.contains(messageText)) return State.RUNNING;
        return State.MISSING;
    }

    static Result cached(String messageText) {
        synchronized (CACHE) {
            return CACHE.get(messageText);
        }
    }

    /** 发起异步分析；结果回调（调用方负责 post 到 UI 线程）。 */
    static void analyzeAsync(JevConfig cfg, List<String[]> context, String messageText, Callback cb) {
        if (!IN_FLIGHT.add(messageText)) return;
        run(cfg, context, messageText, cb);
    }

    /** 忽略缓存强制重新分析。 */
    static void reanalyze(JevConfig cfg, List<String[]> context, String messageText, Callback cb) {
        synchronized (CACHE) {
            CACHE.remove(messageText);
        }
        if (!IN_FLIGHT.add(messageText)) return;
        run(cfg, context, messageText, cb);
    }

    private static void run(JevConfig cfg, List<String[]> context, String messageText, Callback cb) {
        EXECUTOR.execute(() -> {
            Result r;
            try {
                String content = callApi(cfg, context, messageText);
                r = new Result(render(content), null);
            } catch (Throwable t) {
                r = new Result(null, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            } finally {
                IN_FLIGHT.remove(messageText);
            }
            synchronized (CACHE) {
                CACHE.put(messageText, r);
                while (CACHE.size() > CACHE_MAX) {
                    String oldest = CACHE.keySet().iterator().next();
                    CACHE.remove(oldest);
                }
            }
            try {
                cb.onResult(r);
            } catch (Throwable t) {
                JevLog.e("callback failed", t);
            }
        });
    }

    // ---------------------------------------------------------------- api

    private static final String SYSTEM_PROMPT =
            "你是 Jev，一个微信聊天决策引擎，底层由 DeepSeek 驱动。用户会给你一段聊天记录和“最新一条对方消息”，"
                    + "你只输出一个 JSON 对象，禁止输出解释、前后缀或 markdown 代码块。\n"
                    + "JSON 字段：\n"
                    + "{\"q\":\"一句概括当前要判断的核心问题的问句\","
                    + "\"yes\":0,\"no\":0,"
                    + "\"intents\":[{\"label\":\"真实意图短语\",\"p\":0}],"
                    + "\"danger\":0,"
                    + "\"actions\":[{\"label\":\"应对动作短语\",\"p\":0}],"
                    + "\"advice\":\"一句具体的建议\","
                    + "\"reply\":\"一条可以直接发送的回复草稿\"}\n"
                    + "规则：\n"
                    + "1. yes/no 是对 q 的判断概率，均为 0-100 整数且相加约等于 100。\n"
                    + "2. intents 给 2~4 个可能的真实意图，p 为 0-100 整数，总和约 100，按概率降序。\n"
                    + "3. danger 是局势危险等级（对方情绪、关系恶化、吵架风险），0 最安全，10 最危险。\n"
                    + "4. actions 给 2~4 个应对动作，p 为 0-100 整数，总和约 100，按推荐度降序。\n"
                    + "5. advice 给一句最直接的行动建议。\n"
                    + "6. reply 起草一条贴合当前语境、可直接发出的回复（不超过 60 字）；如果现在不该回复，就写“先不要回复”。\n"
                    + "7. 全部使用与聊天相同的语言（默认简体中文），短语要具体、口语化，不要空泛。";

    private static String callApi(JevConfig cfg, List<String[]> context, String messageText) throws Exception {
        StringBuilder user = new StringBuilder();
        user.append("[聊天记录，最早在上]\n");
        for (String[] m : context) {
            user.append("我".equals(m[0]) ? "我: " : "对方: ").append(m[1]).append('\n');
        }
        user.append("[最新一条对方消息]\n").append(messageText).append("\n请只输出 JSON。");

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 500);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        messages.put(new JSONObject().put("role", "user").put("content", user.toString()));
        body.put("messages", messages);

        HttpURLConnection conn = (HttpURLConnection) new URL(cfg.apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
        conn.setRequestProperty("X-Title", "JevForWechat");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = readAll(in);
        if (code >= 400) {
            throw new RuntimeException("HTTP " + code + ": " + abbreviate(resp, 180));
        }

        JSONObject jo = new JSONObject(resp);
        JSONArray choices = jo.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new RuntimeException("响应没有 choices: " + abbreviate(resp, 180));
        }
        String content = choices.getJSONObject(0).optJSONObject("message").optString("content", "");
        if (content.trim().isEmpty()) {
            throw new RuntimeException("模型返回了空内容");
        }
        return content;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- parse & render

    /** 从模型输出里抠出 JSON 并渲染成截图样式的多行文本。 */
    static String render(String modelOutput) throws Exception {
        String s = modelOutput.trim();
        int lt = s.indexOf("```");
        if (lt >= 0) {
            int close = s.indexOf("```", lt + 3);
            if (close > lt) s = s.substring(lt + 3, close);
        }
        int b = s.indexOf('{');
        int e = s.lastIndexOf('}');
        if (b < 0 || e <= b) throw new RuntimeException("输出里没有 JSON");
        JSONObject o = new JSONObject(s.substring(b, e + 1));

        StringBuilder sb = new StringBuilder();
        sb.append("Jev:\n");
        sb.append(o.optString("q", "").trim()).append('\n');
        sb.append("- 是: ").append(o.optInt("yes", 50)).append("%\n");
        sb.append("- 不是: ").append(o.optInt("no", 50)).append("%\n");

        JSONArray intents = o.optJSONArray("intents");
        if (intents != null && intents.length() > 0) {
            sb.append("当前真实意图\n");
            appendRanked(sb, intents);
        }

        int danger = o.optInt("danger", -1);
        if (danger >= 0) sb.append("危险等级: ").append(danger).append("/10\n");

        JSONArray actions = o.optJSONArray("actions");
        if (actions != null && actions.length() > 0) {
            sb.append("最佳动作\n");
            appendRanked(sb, actions);
        }

        String advice = o.optString("advice", "").trim();
        if (!advice.isEmpty()) {
            sb.append("建议动作\n").append(advice).append('\n');
        }

        String reply = o.optString("reply", "").trim();
        if (!reply.isEmpty()) {
            sb.append("DeepSeek 建议回复\n").append(reply);
        }
        return sb.toString().trim();
    }

    private static void appendRanked(StringBuilder sb, JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String label = it.optString("label", "").trim();
            int p = it.optInt("p", 0);
            if (label.isEmpty()) continue;
            sb.append("- ").append(label).append(": ").append(p).append("%\n");
        }
    }

    private static String abbreviate(String s, int max) {
        String one = s.replace('\n', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
