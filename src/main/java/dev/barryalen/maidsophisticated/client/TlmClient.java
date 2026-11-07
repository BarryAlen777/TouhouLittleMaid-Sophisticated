package dev.barryalen.maidsophisticated.client;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.MaidContainerGuiEvent;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.barryalen.maidsophisticated.ModNetwork;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 车万女仆在场时才加载的客户端挂钩：注册标签页 Screen，
 * 并在女仆主界面顶部第四个空位加上背包扣标签。
 */
public final class TlmClient {
    private TlmClient() {}

    public static void init() {
        // 客户端没装精妙背包时，这个界面既看不到背包内容、也打不开原生界面，干脆不挂入口，
        // 免得点开一个注定用不了的标签页，或者收到精妙背包的界面包时缺类出错
        if (!dev.barryalen.maidsophisticated.MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        // 界面注册改走 RegisterMenuScreensEvent（见 ModClientSetup），这里只挂游戏总线事件
        NeoForge.EVENT_BUS.register(MaidBackpackRenderer.class);
        NeoForge.EVENT_BUS.register(TlmClient.class);
        // 换界面（女仆主界面 ↔ 本模组标签页 ↔ 精妙原生界面）时把鼠标放回原位，不跳窗口中心
        GuiSwapCursorFix.init();
    }

    @SubscribeEvent
    public static void onMaidGuiInit(MaidContainerGuiEvent.Init event) {
        AbstractMaidContainerGui<?> gui = event.getGui();
        EntityMaid maid = gui.getMaid();
        if (maid == null) {
            return;
        }
        int maidId = maid.getId();
        event.addButton("maid_sophisticated_tab",
                new MaidSophTabButton(event.getLeftPos() + 169, event.getTopPos() + 5, false,
                        b -> ModNetwork.sendOpenTab(maidId)));
    }
}
