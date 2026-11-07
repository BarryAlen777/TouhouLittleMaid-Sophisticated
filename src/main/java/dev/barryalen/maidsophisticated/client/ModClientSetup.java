package dev.barryalen.maidsophisticated.client;

import dev.barryalen.maidsophisticated.MaidSophisticated;
import dev.barryalen.maidsophisticated.ModMenus;
import dev.barryalen.maidsophisticated.gui.MaidSophTabContainer;
import dev.barryalen.maidsophisticated.gui.MaidSophTabScreen;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = MaidSophisticated.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModClientSetup {
    private ModClientSetup() {}

    /**
     * 1.21.1 起 MenuScreens.register 收归私有，界面必须走这个 mod 总线事件注册。
     * 与 TlmClient.init 同样的守卫：车万女仆和精妙背包都在场才挂这个标签页。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        if (MaidSophisticated.touhouLittleMaidLoaded()
                && MaidSophisticated.sophisticatedBackpacksLoaded()
                && ModMenus.MAID_SOPH_TAB != null) {
            // 注册表里存的是通配 MenuType<?>，泛型推断不出 M，需要显式转成容器类型（与 1.20.1 时同款处理）
            event.register((MenuType<MaidSophTabContainer>) (MenuType<?>) ModMenus.MAID_SOPH_TAB.get(),
                    MaidSophTabScreen::new);
        }
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmClient.init();
            }
        });
    }
}
