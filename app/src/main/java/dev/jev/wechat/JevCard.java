package dev.jev.wechat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 消息下方的灰色 Jev 分析卡片（截图里的样式）。
 *
 * 交互：点按折叠/展开；长按重新分析。卡片通过 tag 识别，绑定重用时先移除旧卡片。
 */
final class JevCard {

    static final String TAG_KEY = "jev_card";
    static final Object TAG_VALUE = new Object();

    private JevCard() {
    }

    static boolean attached(View item) {
        return item.findViewWithTag(TAG_VALUE) != null;
    }

    static void remove(View item) {
        View old = item.findViewWithTag(TAG_VALUE);
        if (old != null && old.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) old.getParent()).removeView(old);
        }
    }

    /** 分析中占位卡片。 */
    static LinearLayout loading(Context ctx, int marginStart) {
        LinearLayout card = base(ctx, marginStart);
        TextView t = new TextView(ctx);
        t.setText("⚡ Jev 分析中…");
        t.setTextSize(12f);
        t.setTextColor(0xFF999999);
        card.addView(t);
        return card;
    }

    /** 失败卡片：长按重试。 */
    static LinearLayout error(Context ctx, int marginStart, String message) {
        LinearLayout card = base(ctx, marginStart);
        TextView t = new TextView(ctx);
        t.setText("✕ Jev 分析失败：" + message + "\n长按卡片重试");
        t.setTextSize(12f);
        t.setTextColor(0xFFB25B00);
        card.addView(t);
        return card;
    }

    /** 未配置提示卡片。 */
    static LinearLayout unconfigured(Context ctx, int marginStart) {
        LinearLayout card = base(ctx, marginStart);
        TextView t = new TextView(ctx);
        t.setText("Jev：尚未配置 API（长按消息 → Jev设置）");
        t.setTextSize(12f);
        t.setTextColor(0xFF999999);
        card.addView(t);
        return card;
    }

    /**
     * 结果卡片：第一行为标题（始终显示），其余行点按折叠/展开，长按重新分析。
     *
     * @param onRetry 长按回调；null 则不响应长按
     */
    static LinearLayout result(Context ctx, int marginStart, String fullText, Runnable onRetry) {
        LinearLayout card = base(ctx, marginStart);
        String[] lines = fullText.split("\n");

        TextView title = line(ctx, 0, lines.length > 0 ? lines[0] : "", true);
        card.addView(title);

        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i];
            boolean isSection = ln.equals("当前真实意图") || ln.equals("最佳动作") || ln.equals("建议动作");
            TextView tv = line(ctx, isSection ? 2 : 1, ln, i == 1 || isSection);
            card.addView(tv);
        }
        if (lines.length <= 1) {
            TextView t = new TextView(ctx);
            t.setText("(空结果)");
            t.setTextSize(12f);
            t.setTextColor(0xFF999999);
            card.addView(t);
        }

        card.setOnClickListener(v -> {
            boolean collapsed = Boolean.TRUE.equals(v.getTag(R.id.jev_collapsed));
            for (int i = 1; i < card.getChildCount(); i++) {
                card.getChildAt(i).setVisibility(collapsed ? View.VISIBLE : View.GONE);
            }
            v.setTag(R.id.jev_collapsed, !collapsed);
        });
        if (onRetry != null) {
            card.setOnLongClickListener(v -> {
                onRetry.run();
                return true;
            });
        }
        return card;
    }

    private static TextView line(Context ctx, int style, String text, boolean emphasize) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        switch (style) {
            case 0: // 标题
                tv.setTextSize(13f);
                tv.setTextColor(0xFF333333);
                break;
            case 2: // 小节标题
                tv.setTextSize(11f);
                tv.setTextColor(0xFF999999);
                break;
            default: // 概率/内容行
                tv.setTextSize(12f);
                tv.setTextColor(0xFF555555);
        }
        if (emphasize && style == 1) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(0xFF333333);
        }
        if (style == 2) {
            tv.setPadding(0, dp(ctx, 4), 0, 0);
        }
        return tv;
    }

    private static LinearLayout base(Context ctx, int marginStart) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag(TAG_VALUE);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF4F4F4);
        bg.setCornerRadius(dp(ctx, 8));
        card.setBackground(bg);
        card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(marginStart, dp(ctx, 2), dp(ctx, 36), dp(ctx, 6));
        card.setLayoutParams(lp);
        card.setGravity(Gravity.START);
        return card;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
