package dev.barryalen.maidsophisticated.client;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.TaskButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;

/**
 * "鼠标也点得动"的女仆工作模式按钮。
 *
 * <p>车万女仆自己的 {@link TaskButton} 把 {@code mouseClicked} 第一行写成了
 * {@code if (!enable) return false;}——客户端觉得这个模式现在不能选，鼠标点上去就完全没反应。
 * 这个判断在女仆首页是合理的（点不动就当没有这个按钮），但放在我们这一页就不对了：
 * 客户端这份女仆物品栏<b>看不到精妙背包里的东西</b>（接通背包的补丁只在服务端接，
 * 客户端那份是同步下来的影子，写进去会和真实数据打架）。所以"食物只在精妙背包里"时，
 * 客户端会把"喂食"算成选不了，服务端那边条件其实是满足的。
 * 正因为这样，我们这一页点任何模式都是<b>发包让服务端裁决</b>（见
 * {@code MaidSophTabScreen#switchTask}：服务端会重新按真实状态查一边，不合格直接忽略）。
 * 结果就是：裁决逻辑做好了，鼠标却被女仆的门禁挡在门外，只有键盘 Tab+回车才走得通。</p>
 *
 * <p>这里只改掉鼠标这一层：照原版 {@code AbstractWidget#mouseClicked} 的判定顺序
 * （active → visible → 按键合法 → 命中区域）自己走一遍，不碰 {@code enable}。
 * 灰化外观和"为什么不满足条件"的悬停说明都还是女仆自己那套，玩家看着没区别，
 * 但点下去一定会把请求发出去，由服务端说了算。</p>
 */
public final class MaidSophTaskButton extends TaskButton {

    public MaidSophTaskButton(IMaidTask task, boolean enabled, int x, int y, int width, int height,
                              int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture,
                              int textureWidth, int textureHeight, Button.OnPress onPress) {
        super(task, enabled, x, y, width, height, xTexStart, yTexStart, yDiffTex, texture,
                textureWidth, textureHeight, onPress);
    }

    /**
     * 绕开 {@code TaskButton} 的 enable 门禁。判定顺序和原版
     * {@code AbstractWidget#mouseClicked} 一致，只是不再先看 {@code enable} 的脸色；
     * 命中后同样播按下音效并走 {@code onClick}（基类里它就是调 {@code onPress}）。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (this.active && this.visible && this.isValidClickButton(mouseButton) && this.clicked(mouseX, mouseY)) {
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            this.onClick(mouseX, mouseY);
            return true;
        }
        return false;
    }
}
