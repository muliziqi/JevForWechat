package dev.jev.wechat;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 处理聊天列表的每个 item：
 * 1. 判断这条消息是对方发的（气泡在左侧）还是自己发的（右侧）；
 * 2. 把对方消息记录进上下文缓冲；
 * 3. 对列表底部 N 条内的对方消息，在其气泡下方注入 Jev 分析卡片。
 *
 * 不依赖任何微信混淆类名：只看 TextView 内容与左右位置。
 * 为了避免把通讯录/设置等同样“文字在左”的列表误判成聊天，只有当同一个列表里
 * 见过至少一条右侧消息（自己发过的）才开始注入卡片。
 */
final class ChatItemProcessor {

    /** 底部多少条之内自动注入并分析。 */
    private static final int BOTTOM_ZONE = 3;
    private static final int BUFFER_MAX = 20;
    private static final int TEXT_MAX = 600;

    /** 每个列表（adapter）一份状态。 */
    private static final class ListState {
        final LinkedHashMap<String, String[]> buffer = new LinkedHashMap<>();
        boolean seenOutgoing = false;
    }

    private static final LinkedHashMap<Object, ListState> STATES = new LinkedHashMap<>();

    private ChatItemProcessor() {
    }

    // ================================================================ entry

    /** 由 bind 钩子调用（主线程）。 */
    static void process(Object adapter, View item, int position, int count) {
        processImpl(adapter, item, position, count, false);
    }

    private static void processImpl(Object adapter, View item, int position, int count, boolean forceZone) {
        try {
            if (item == null || !(item instanceof ViewGroup)) return;
            int width = item.getWidth();
            if (width == 0) {
                // 尚未布局，等一帧再试（用 tag 防止重复排程）
                if (item.getTag(dev.jev.wechat.R.id.jev_pending) == null) {
                    item.setTag(dev.jev.wechat.R.id.jev_pending, Boolean.TRUE);
                    item.post(() -> {
                        try {
                            item.setTag(dev.jev.wechat.R.id.jev_pending, null);
                            processImpl(adapter, item, position, count, forceZone);
                        } catch (Throwable t) {
                            JevLog.e("process retry failed", t);
                        }
                    });
                }
                return;
            }

            ListState st = stateFor(adapter);

            TextView main = findMainText(item);
            if (main == null) {
                JevCard.remove(item); // 时间戳/系统行/图片行：清掉旧卡片
                return;
            }
            String text = itemText(item);
            if (text.isEmpty()) {
                JevCard.remove(item);
                return;
            }

            int left = leftWithin(item, main);
            float ratio = left / (float) width;
            boolean incoming = ratio < 0.42f;
            boolean outgoing = ratio > 0.58f;
            if (!incoming && !outgoing) {
                JevCard.remove(item); // 居中的系统消息
                return;
            }
            if (outgoing) {
                st.seenOutgoing = true;
                JevCard.remove(item); // 自己的消息不注入
                return;
            }
            if (!st.seenOutgoing) {
                return; // 还没见过右侧消息：可能是通讯录等列表，先不注入
            }

            boolean inZone = forceZone || (count > 0 && position >= count - BOTTOM_ZONE);

            // 记录上下文（只记底部区域，避免滚动历史时打乱顺序）
            if (inZone) {
                st.buffer.remove(text);
                st.buffer.put(text, new String[]{"对方", text});
                while (st.buffer.size() > BUFFER_MAX) {
                    String oldest = st.buffer.keySet().iterator().next();
                    st.buffer.remove(oldest);
                }
            }

            injectOrUpdate(adapter, st, item, text, inZone, left);
        } catch (Throwable t) {
            JevLog.e("process item failed", t);
        }
    }

    // ================================================================ card logic

    private static void injectOrUpdate(Object adapter, ListState st, View item,
                                       String text, boolean inZone, int mainLeft) {
        if (!inZone) {
            JevCard.remove(item);
            return;
        }

        ViewGroup insertParent = findInsertParent(item);
        if (insertParent == null) {
            JevLog.i("no vertical container found for item, skip inject");
            return;
        }
        int insertIndex = indexOfDescendant(insertParent, item, mainTextViewOf(item));
        int marginStart = Math.max(JevConfig.dp(item.getContext(), 8), mainLeft - JevConfig.dp(item.getContext(), 6));

        JevCard.remove(item);
        JevConfig cfg = JevConfig.load(item.getContext());

        LinearLayout card;
        if (cfg == null) {
            card = JevCard.unconfigured(item.getContext(), marginStart);
        } else {
            JevEngine.State s = JevEngine.stateOf(text);
            if (s == JevEngine.State.RUNNING) {
                card = JevCard.loading(item.getContext(), marginStart);
            } else if (s == JevEngine.State.DONE) {
                JevEngine.Result r = JevEngine.cached(text);
                if (r.error != null) {
                    card = JevCard.error(item.getContext(), marginStart, r.error);
                    bindRetry(card, adapter, st, item, text);
                } else {
                    card = JevCard.result(item.getContext(), marginStart, r.text, null);
                    bindRetry(card, adapter, st, item, text);
                }
            } else {
                card = JevCard.loading(item.getContext(), marginStart);
                trigger(adapter, st, item, text, cfg);
            }
        }

        try {
            insertParent.addView(card, Math.min(insertIndex, insertParent.getChildCount()));
        } catch (Throwable t) {
            JevLog.e("inject card failed", t);
        }
    }

    private static void trigger(Object adapter, ListState st, View item, String text, JevConfig cfg) {
        JevEngine.analyzeAsync(cfg, snapshot(st, text), text, result ->
                item.post(() -> {
                    try {
                        // item 可能已被复用绑到别的消息：确认当前内容还包含这条消息再渲染
                        String current = itemText(item);
                        if (!current.contains(text)) return;
                        ViewGroup parent = findInsertParent(item);
                        if (parent == null) return;
                        int marginStart = Math.max(JevConfig.dp(item.getContext(), 8),
                                leftWithin(item, mainTextViewOf(item)) - JevConfig.dp(item.getContext(), 6));
                        JevCard.remove(item);
                        LinearLayout card = result.error != null
                                ? JevCard.error(item.getContext(), marginStart, result.error)
                                : JevCard.result(item.getContext(), marginStart, result.text, null);
                        bindRetry(card, adapter, st, item, text);
                        parent.addView(card, Math.min(indexOfDescendant(parent, item, mainTextViewOf(item)),
                                parent.getChildCount()));
                    } catch (Throwable t) {
                        JevLog.e("render result failed", t);
                    }
                }));
    }

    /** 长按卡片重新分析。 */
    private static void bindRetry(LinearLayout card, Object adapter, ListState st, View item, String text) {
        card.setOnLongClickListener(v -> {
            try {
                JevConfig cfg = JevConfig.load(item.getContext());
                if (cfg == null) return false;
                ViewGroup parent = findInsertParent(item);
                if (parent == null) return false;
                JevCard.remove(item);
                int marginStart = Math.max(JevConfig.dp(item.getContext(), 8),
                        leftWithin(item, mainTextViewOf(item)) - JevConfig.dp(item.getContext(), 6));
                LinearLayout loading = JevCard.loading(item.getContext(), marginStart);
                parent.addView(loading, Math.min(indexOfDescendant(parent, item, mainTextViewOf(item)),
                        parent.getChildCount()));
                JevEngine.reanalyze(cfg, snapshot(st, text), text, result ->
                        item.post(() -> processImpl(adapter, item, -1, 0, true)));
            } catch (Throwable t) {
                JevLog.e("retry failed", t);
            }
            return true;
        });
    }

    private static List<String[]> snapshot(ListState st, String excludeText) {
        List<String[]> out = new ArrayList<>();
        for (String[] m : st.buffer.values()) {
            if (m[1].equals(excludeText)) continue;
            out.add(m);
        }
        return out;
    }

    /** 手动「Jev分析」用：取缓冲最多那个列表的上下文。 */
    static List<String[]> latestContext(String excludeText) {
        ListState best = null;
        for (ListState st : STATES.values()) {
            if (best == null || st.buffer.size() > best.buffer.size()) best = st;
        }
        return best == null ? new ArrayList<>() : snapshot(best, excludeText);
    }

    private static ListState stateFor(Object adapter) {
        ListState st = STATES.get(adapter);
        if (st == null) {
            st = new ListState();
            STATES.put(adapter, st);
            while (STATES.size() > 8) {
                Object eldest = STATES.keySet().iterator().next();
                STATES.remove(eldest);
            }
        }
        return st;
    }

    // ================================================================ view helpers

    /** item 里“主”文本：最长且不像时间戳的 TextView。 */
    private static TextView findMainText(View item) {
        List<TextView> all = new ArrayList<>();
        collectTextViews(item, all);
        TextView best = null;
        int bestLen = 0;
        for (TextView tv : all) {
            CharSequence cs = tv.getText();
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.isEmpty() || timestampLike(s)) continue;
            if (s.length() > bestLen) {
                best = tv;
                bestLen = s.length();
            }
        }
        return best;
    }

    private static TextView mainTextViewOf(View item) {
        return findMainText(item);
    }

    /** 整个 item 的全部文字（去掉时间戳行），作为这条消息的内容。 */
    private static String itemText(View item) {
        List<TextView> all = new ArrayList<>();
        collectTextViews(item, all);
        StringBuilder sb = new StringBuilder();
        for (TextView tv : all) {
            CharSequence cs = tv.getText();
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.isEmpty() || timestampLike(s)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
            if (sb.length() >= TEXT_MAX) break;
        }
        if (sb.length() > TEXT_MAX) sb.setLength(TEXT_MAX);
        return sb.toString().trim();
    }

    private static void collectTextViews(View root, List<TextView> out) {
        Deque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && out.size() < 40) {
            View v = queue.removeFirst();
            if (v instanceof TextView) {
                out.add((TextView) v);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) queue.addLast(g.getChildAt(i));
            }
        }
    }

    /** 短文本且长得像聊天时间戳（21:37 / 今天 19:12 / 昨天 09:05 / 星期三 11:20 …）。 */
    private static boolean timestampLike(String s) {
        if (s.length() > 14) return false;
        return s.matches("(今天|昨天|前天|星期[一二三四五六日天])?\\s*\\d{1,2}:\\d{2}");
    }

    /** v 相对 root 的左偏移（要求已布局）。 */
    private static int leftWithin(View root, View v) {
        int x = 0;
        View cur = v;
        while (cur != null && cur != root) {
            x += cur.getLeft();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return x;
    }

    /** 从气泡往上找最外层的竖向 LinearLayout 祖先（卡片插在这里，随消息滚动）。 */
    private static ViewGroup findInsertParent(View item) {
        TextView main = mainTextViewOf(item);
        if (main == null) return null;
        ViewGroup best = null;
        ViewGroup parent = main.getParent() instanceof ViewGroup ? (ViewGroup) main.getParent() : null;
        while (parent != null) {
            if (parent instanceof LinearLayout
                    && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
                best = parent;
            }
            if (parent == item) break;
            parent = parent.getParent() instanceof ViewGroup ? (ViewGroup) parent.getParent() : null;
        }
        return best;
    }

    /** descendant 在 ancestor(item) 中的“包含链” child 在 best 里的下一位。 */
    private static int indexOfDescendant(ViewGroup best, View item, View descendant) {
        if (descendant == null) return best.getChildCount();
        View child = descendant;
        while (child != null && child.getParent() != best && child != item) {
            child = child.getParent() instanceof View ? (View) child.getParent() : null;
        }
        if (child == null || !(child.getParent() == best)) return best.getChildCount();
        return ((ViewGroup) best).indexOfChild(child) + 1;
    }
}
