package dev.barryalen.maidsophisticated.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * 治"从一个容器界面换开到另一个容器界面时，鼠标自己跳到窗口正中"。
 *
 * <p>原因：服务端换菜单（{@code closeContainer()} + {@code openMenu()} 连着发，切到本模组
 * 标签页、从标签页打开精妙原生界面都走这条）到客户端就是"旧界面关掉 → 新界面打开"两步。
 * 关掉的一瞬间没有界面可显示，Minecraft 把光标收进 FPS 模式（GLFW 禁用光标），
 * 这一步会把光标挪到窗口中心待命；新界面打开时光标放出来——就停在中心了。
 * 位置本身没有任何"上次在哪"的记忆，玩家的手感当场作废，只能摸回原位。</p>
 *
 * <p>对策：旧容器界面<b>关闭前</b>（{@link ScreenEvent.Closing}，此刻还没被中心吸走）
 * 记下光标原始像素位置；新容器界面打开后的第一帧结束（{@link RenderFrameEvent.Post}，
 *  GLFW 输入模式已稳定）用 {@code glfwSetCursorPos} 放回去。只在两次换屏间隔很短时生效——
 * 关掉界面过了一会儿再开，光标停在哪儿都是玩家自己的决定，不许插手。</p>
 *
 * <p>两个事件都只认"容器界面"：聊天栏、字幕这类界面关了不记账（它们本来不跳，
 * 记账反而会把换界面误判成"要还原"）。</p>
 */
public final class GuiSwapCursorFix {
    /** 旧界面关到新界面开，隔这么久以内才认为是"同一次换页"，还原光标。 */
    private static final long SWAP_WINDOW_MILLIS = 1000L;

    /** Closing 记下的光标位置（窗口原始像素，不是 GUI 缩放坐标）。 */
    private static double rememberedX;
    private static double rememberedY;
    private static long rememberedAt;
    /** 有没有一笔"待还原"：Closing 记下后置真，Opening 判定要用，落帧后清掉。 */
    private static boolean rememberValid;
    /** Opening 判定成立后置真，等第一帧渲染完落位。 */
    private static boolean pendingWarp;

    private GuiSwapCursorFix() {}

    public static void init() {
        NeoForge.EVENT_BUS.register(GuiSwapCursorFix.class);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen)) {
            return;
        }
        MouseSnapshot capture = currentCursor();
        if (capture == null) {
            return;
        }
        rememberedX = capture.x;
        rememberedY = capture.y;
        rememberedAt = Util.getMillis();
        rememberValid = true;
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (!(next instanceof AbstractContainerScreen)
                || Util.getMillis() - rememberedAt > SWAP_WINDOW_MILLIS) {
            // 不是"接着换容器界面"：这笔记录作废。
            // 只清 pendingWarp 不清 rememberValid 的话，先开一个聊天栏/字幕这类界面，
            // 1 秒内再开容器界面，就会拿更早那次的光标位置去还原。
            rememberValid = false;
            pendingWarp = false;
            return;
        }
        pendingWarp = rememberValid;
    }

    @SubscribeEvent
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        if (!pendingWarp) {
            return;
        }
        // 无论成败只落帧一次：这一帧 GLFW 已经按新界面的输入模式就位，改位置不会被再中心的动作盖掉
        pendingWarp = false;
        rememberValid = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen && mc.getWindow() != null) {
            GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), rememberedX, rememberedY);
        }
    }

    /** 读一次光标原始位置；拿不到（窗口未创建等）返回 null。 */
    private static MouseSnapshot currentCursor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return null;
        }
        // MouseHandler 的 xpos/ypos 就是 glfwSetCursorPos 同款的窗口像素坐标，直接成套用
        return new MouseSnapshot(mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
    }

    private record MouseSnapshot(double x, double y) {}
}
