package dev.barryalen.maidsophisticated.client;

import dev.barryalen.maidsophisticated.MaidBackpackData;
import dev.barryalen.maidsophisticated.MaidBackpackView;
import dev.barryalen.maidsophisticated.gui.MaidSophTabContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 两个"服务端发给客户端"的包真正干活的地方。
 *
 * 为什么单独放一个类：这两个包要读 {@code Minecraft} 之类的纯客户端类。
 * 如果处理逻辑写在公共类里，专用服务器加载/校验那个公共类时就可能去解析客户端类，
 * 直接 {@code NoClassDefFoundError} 把服务端带崩。放在这里以后，服务端从头到尾
 * 都不会加载本类；只有在客户端真正收到包时才第一次触碰它。
 */
public final class MaidSyncHandler {
    private MaidSyncHandler() {}

    /** 女仆三个背包槽 + 主背包下标：写进客户端内存缓存，背部渲染直接读它。 */
    public static void handleMaidBackpack(int entityId, List<ItemStack> stacks, boolean showSophisticated,
                                          int mainSlot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // 缓存给背部渲染用（不依赖实体 capability 是否已经建好）
        MaidBackpackView.update(entityId, stacks, showSophisticated, mainSlot);
        Entity entity = mc.level.getEntity(entityId);
        MaidBackpackData.of(entity).ifPresent(data -> data.loadRemote(stacks, showSophisticated, mainSlot));
        MaidBackpackView.logFirstReception(entityId, stacks, showSophisticated, mainSlot);
    }

    /** 标签页整屏内容：只写进"当前正好是那个窗口"的容器，防止串窗。 */
    public static void handleBackpackContent(int windowId, int selected, int scroll, List<ItemStack> stacks,
                                             int total) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (mc.player.containerMenu instanceof MaidSophTabContainer menu && menu.containerId == windowId) {
            menu.applyContentSync(selected, scroll, stacks, total);
        }
    }
}
