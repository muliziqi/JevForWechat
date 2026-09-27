package dev.jev.wechat;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * A1 目标：
 * 1) 只注入 com.tencent.mm（无 Root，经 LSPatch 加载）。
 * 2) 保留 A0 的长按菜单注入：「Jev分析」（手动分析 + 诊断）与「Jev设置」（填 API）。
 * 3) Hook 聊天列表（ListView / RecyclerView，不写死微信混淆类名），
 *    在列表底部最近几条“对方消息”下方自动插入 Jev 分析卡片。
 *    分析由 Jev 决策 Prompt 驱动 DeepSeek / OpenRouter 等 OpenAI 兼容接口完成。
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "JevForWechat";
    private static final String TARGET = "com.tencent.mm";
    private static final String MENU_LABEL = "Jev分析";
    private static final String SETTINGS_LABEL = "Jev设置";

    private static volatile View lastLongPressedView;
    private static volatile String lastLongPressedText = "";
    private static volatile long lastLongPressAt = 0L;
    private static final AtomicBoolean installed = new AtomicBoolean(false);
    private static final Set<Class<?>> HOOKED_ADAPTERS = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded in process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        if (!installed.compareAndSet(false, true)) return;

        log(Log.INFO, TAG, "installing A1 hooks for " + TARGET);
        try {
            installLongPressCapture();
            installPopupHooks();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "menu hook install failed", t);
        }
        try {
            installChatListHooks(param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "chat list hook install failed", t);
        }
        log(Log.INFO, TAG, "A1 hooks installed");
    }

    // ================================================================ A0: long press + menu

    private void installLongPressCapture() throws Exception {
        Method performLongClick = View.class.getDeclaredMethod("performLongClick");
        performLongClick.setAccessible(true);

        hook(performLongClick)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object self = chain.getThisObject();
                    if (self instanceof View) {
                        View view = (View) self;
                        String text = extractText(view);
                        if (!text.isEmpty()) {
                            lastLongPressedView = view;
                            lastLongPressedText = text;
                            lastLongPressAt = SystemClock.uptimeMillis();
                        }
                    }
                    return chain.proceed();
                });
    }

    private void installPopupHooks() throws Exception {
        hookPopupMethod("showAtLocation", View.class, int.class, int.class, int.class);
        hookPopupMethod("showAsDropDown", View.class);
        hookPopupMethod("showAsDropDown", View.class, int.class, int.class);
        hookPopupMethod("showAsDropDown", View.class, int.class, int.class, int.class);
    }

    private void hookPopupMethod(String name, Class<?>... args) throws Exception {
        Method method = PopupWindow.class.getDeclaredMethod(name, args);
        method.setAccessible(true);
        hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof PopupWindow) {
                        PopupWindow popup = (PopupWindow) self;
                        View content = popup.getContentView();
                        if (content != null) {
                            content.post(() -> tryInjectMenuItem(popup));
                        }
                    }
                    return result;
                });
    }

    private void tryInjectMenuItem(PopupWindow popup) {
        try {
            if (SystemClock.uptimeMillis() - lastLongPressAt > 2500L) return;
            View content = popup.getContentView();
            if (content == null) return;

            TextView translate = findTextView(content, "翻译");
            if (translate == null) return;
            if (!containsAnyText(content, "复制", "转发", "收藏", "引用", "删除")) return;

            ViewParentInfo p = parentOf(translate);
            if (p == null) return;

            View anchor = lastLongPressedView;
            String captured = lastLongPressedText;

            if (findTextView(content, MENU_LABEL) == null) {
                TextView jev = cloneMenuTextView(translate);
                jev.setText(MENU_LABEL);
                jev.setOnClickListener(v -> {
                    try {
                        popup.dismiss();
                    } catch (Throwable ignored) {
                    }
                    runOneShotAnalysis(anchor, captured);
                });
                addNextTo(translate, jev);
                log(Log.INFO, TAG, "inserted Jev menu item");
            }

            if (findTextView(content, SETTINGS_LABEL) == null) {
                TextView settings = cloneMenuTextView(translate);
                settings.setText(SETTINGS_LABEL);
                settings.setOnClickListener(v -> {
                    try {
                        popup.dismiss();
                    } catch (Throwable ignored) {
                    }
                    if (anchor != null) {
                        JevConfig.showSettingsDialog(anchor);
                    }
                });
                addNextTo(translate, settings);
                log(Log.INFO, TAG, "inserted Jev settings item");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "menu injection failed", t);
        }
    }

    private static void addNextTo(TextView ref, View item) {
        ViewParentInfo p = parentOf(ref);
        if (p == null) return;
        ViewGroup.LayoutParams lp = copyLayoutParams(ref);
        int at = Math.min(p.index + 1, p.parent.getChildCount());
        if (lp != null) {
            p.parent.addView(item, at, lp);
        } else {
            p.parent.addView(item, at);
        }
    }

    /** 手动点「Jev分析」：对捕获的文本立即做一次分析，弹窗显示。 */
    private static void runOneShotAnalysis(View anchor, String text) {
        try {
            if (anchor == null) return;
            Context base = anchor.getContext();
            if (text == null || text.isEmpty()) {
                Toast.makeText(base, "Jev：没有读取到文字", Toast.LENGTH_LONG).show();
                return;
            }
            JevConfig cfg = JevConfig.load(base);
            if (cfg == null) {
                Toast.makeText(base, "Jev：尚未配置 API，请长按消息 → Jev设置", Toast.LENGTH_LONG).show();
                return;
            }
            JevEngine.State s = JevEngine.stateOf(text);
            if (s == JevEngine.State.DONE) {
                JevEngine.Result r = JevEngine.cached(text);
                showAnalysisDialog(base, r.error != null ? "分析失败：" + r.error : r.text);
                return;
            }
            Toast.makeText(base, "Jev 分析中…", Toast.LENGTH_SHORT).show();
            JevEngine.analyzeAsync(cfg, ChatItemProcessor.latestContext(text), text, result ->
                    MAIN.post(() -> showAnalysisDialog(base,
                            result.error != null ? "分析失败：" + result.error : result.text)));
        } catch (Throwable t) {
            JevLog.e("one-shot analysis failed", t);
        }
    }

    private static void showAnalysisDialog(Context ctx, String text) {
        try {
            if (ctx instanceof android.app.Activity && ((android.app.Activity) ctx).isFinishing()) return;
            Context themed = new ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            TextView tv = new TextView(themed);
            tv.setText(text);
            tv.setTextSize(13f);
            tv.setTextColor(0xFF333333);
            int pad = JevConfig.dp(themed, 24);
            tv.setPadding(pad, pad / 2, pad, 0);
            ScrollView sv = new ScrollView(themed);
            sv.addView(tv);
            new AlertDialog.Builder(themed)
                    .setTitle("Jev 分析")
                    .setView(sv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            JevLog.e("show dialog failed", t);
        }
    }

    // ================================================================ A1: chat list hooks

    private void installChatListHooks(ClassLoader cl) throws Exception {
        // 路径一：老式 ListView（AbsListView.setAdapter 是所有子类的必经之路）
        Method absSetAdapter = android.widget.AbsListView.class
                .getDeclaredMethod("setAdapter", android.widget.ListAdapter.class);
        hook(absSetAdapter)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object adapter = chain.getArg(0);
                    if (adapter != null) {
                        hookAdapterBind(adapter, false);
                    }
                    return result;
                });

        // 路径二：androidx RecyclerView（微信 8.x 聊天页大概率是它）
        hookRecyclerViewSetAdapter(cl, "androidx.recyclerview.widget.RecyclerView");
        // 路径三：老 support 库兜底
        hookRecyclerViewSetAdapter(cl, "android.support.v7.widget.RecyclerView");
    }

    private void hookRecyclerViewSetAdapter(ClassLoader cl, String className) {
        try {
            Class<?> rv = Class.forName(className, false, cl);
            Class<?> adapterCls = Class.forName(className + "$Adapter", false, cl);
            Method setAdapter = rv.getDeclaredMethod("setAdapter", adapterCls);
            hook(setAdapter)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object adapter = chain.getArg(0);
                        if (adapter != null) {
                            hookAdapterBind(adapter, true);
                        }
                        return result;
                    });
            JevLog.i("hooked setAdapter on " + className);
        } catch (Throwable t) {
            JevLog.i("recycler class not available: " + className + " (" + t.getMessage() + ")");
        }
    }

    /** 给具体 adapter 动态挂 bind 钩子；同一 adapter 类只挂一次。 */
    private void hookAdapterBind(Object adapter, boolean recycler) {
        try {
            Class<?> cls = adapter.getClass();
            synchronized (HOOKED_ADAPTERS) {
                if (!HOOKED_ADAPTERS.add(cls)) return;
            }

            if (recycler) {
                Method bind = findDeclaredMethod(cls, "onBindViewHolder", 2);
                if (bind == null) {
                    JevLog.i("no onBindViewHolder found on " + cls.getName());
                    return;
                }
                hook(bind)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object holder = chain.getArg(0);
                                int position = (Integer) chain.getArg(1);
                                View item = itemViewOf(holder);
                                if (item != null) {
                                    int count = invokeItemCount(adapter);
                                    ChatItemProcessor.process(adapter, item, position, count);
                                }
                            } catch (Throwable t) {
                                JevLog.e("recycler bind process failed", t);
                            }
                            return result;
                        });
                JevLog.i("hooked onBindViewHolder on " + cls.getName());
            } else {
                Method getView = findDeclaredMethod(cls, "getView", 3);
                if (getView == null) {
                    JevLog.i("no getView found on " + cls.getName());
                    return;
                }
                hook(getView)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                if (result instanceof View) {
                                    int position = (Integer) chain.getArg(0);
                                    int count = adapter instanceof android.widget.Adapter
                                            ? ((android.widget.Adapter) adapter).getCount() : -1;
                                    ChatItemProcessor.process(adapter, (View) result, position, count);
                                }
                            } catch (Throwable t) {
                                JevLog.e("list bind process failed", t);
                            }
                            return result;
                        });
                JevLog.i("hooked getView on " + cls.getName());
            }
        } catch (Throwable t) {
            JevLog.e("hookAdapterBind failed", t);
        }
    }

    private static int invokeItemCount(Object adapter) {
        try {
            Method m = findDeclaredMethod(adapter.getClass(), "getItemCount", 0);
            if (m != null) {
                Object v = m.invoke(adapter);
                return v instanceof Integer ? (Integer) v : -1;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** RecyclerView.ViewHolder.itemView（混淆环境下按字段类型兜底查找）。 */
    private static View itemViewOf(Object holder) {
        if (holder == null) return null;
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField("itemView");
                f.setAccessible(true);
                Object v = f.get(holder);
                if (v instanceof View) return (View) v;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                break;
            }
        }
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!View.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v instanceof View) return (View) v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Method findDeclaredMethod(Class<?> start, String name, int paramCount) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    // ================================================================ view utils (A0)

    private static TextView cloneMenuTextView(TextView src) {
        TextView out = new TextView(src.getContext());
        out.setTextColor(src.getTextColors());
        out.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
        out.setGravity(src.getGravity() == Gravity.NO_GRAVITY ? Gravity.CENTER : src.getGravity());
        out.setPadding(src.getPaddingLeft(), src.getPaddingTop(), src.getPaddingRight(), src.getPaddingBottom());
        out.setMinWidth(src.getMinWidth());
        out.setMinHeight(src.getMinHeight());
        out.setSingleLine(src.isSingleLine());
        Drawable bg = src.getBackground();
        if (bg != null) out.setBackground(bg.getConstantState() != null ? bg.getConstantState().newDrawable() : bg);
        return out;
    }

    private static ViewGroup.LayoutParams copyLayoutParams(View src) {
        ViewGroup.LayoutParams lp = src.getLayoutParams();
        if (lp == null) return null;
        if (lp instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp);
        }
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) lp);
        }
        return new ViewGroup.LayoutParams(lp);
    }

    private static ViewParentInfo parentOf(View view) {
        if (!(view.getParent() instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) view.getParent();
        return new ViewParentInfo(parent, parent.indexOfChild(view));
    }

    private static TextView findTextView(View root, String exact) {
        if (root instanceof TextView) {
            CharSequence cs = ((TextView) root).getText();
            if (cs != null && exact.contentEquals(cs.toString().trim())) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findTextView(g.getChildAt(i), exact);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static boolean containsAnyText(View root, String... values) {
        for (String value : values) {
            if (findTextView(root, value) != null) return true;
        }
        return false;
    }

    private static String extractText(View root) {
        StringBuilder sb = new StringBuilder();
        Deque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && sb.length() < 800) {
            View v = queue.removeFirst();
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) {
                    String s = cs.toString().trim();
                    if (!s.isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(s);
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) queue.addLast(g.getChildAt(i));
            }
        }
        return sb.toString().trim();
    }

    private static final class ViewParentInfo {
        final ViewGroup parent;
        final int index;
        ViewParentInfo(ViewGroup parent, int index) {
            this.parent = parent;
            this.index = index;
        }
    }
}
