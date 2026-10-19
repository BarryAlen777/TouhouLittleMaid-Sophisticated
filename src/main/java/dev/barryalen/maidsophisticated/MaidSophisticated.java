package dev.barryalen.maidsophisticated;

import dev.barryalen.maidsophisticated.advancement.FirstMaidBackpackTrigger;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Touhou Little Maid：Sophisticated（启动器显示名：车万女仆：这很精妙）模组入口。
 *
 * 设计要点：
 * - 女仆拥有一个独立的"精妙背包槽"（最多同时装备 3 个），通过 capability 挂在女仆实体上，
 *   完全不占用女仆自身饰品格（Bauble）或任何玩家装备槽。
 * - 打开女仆界面时，顶部第四个空标签就是本模组的"精妙背包"页：三个专属槽 +
 *   内容网格（解锁后显示精妙背包内部，带滚动条）。原生界面按钮仍可跳到精妙背包自己的双页签。
 * - 精妙背包（sophisticatedbackpacks）或车万女仆（touhou_little_maid）没装时，
 *   所有相关逻辑都会被安全跳过，不会崩溃。
 */
@Mod(MaidSophisticated.MOD_ID)
public final class MaidSophisticated {
    public static final String MOD_ID = "maid_sophisticated";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final String SB_MOD_ID = "sophisticatedbackpacks";
    public static final String TLM_MOD_ID = "touhou_little_maid";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public MaidSophisticated() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();

        // 触发各注册器类的静态初始化，把它们往 DeferredRegister 里塞的注册都建好
        ModItems.init();
        ModMenus.init();

        ITEMS.register(bus);
        ModMenus.MENUS.register(bus);

        // 向精妙背包注册"女仆背包槽"处理器（只在精妙背包在场时）
        if (ModList.get().isLoaded(SB_MOD_ID)) {
            SbCompat.registerProvider();
        }

        bus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(Events.class);
        // 拾取相关的挂钩用到了车万女仆自己的事件类，没装女仆模组时不能注册
        if (ModList.get().isLoaded(TLM_MOD_ID)) {
            MinecraftForge.EVENT_BUS.register(PickupEvents.class);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 自定义进度触发器："这就很妙"
            CriteriaTriggers.register(FirstMaidBackpackTrigger.INSTANCE);
            ModNetwork.register();
        });
    }

    public static boolean sophisticatedBackpacksLoaded() {
        return ModList.get().isLoaded(SB_MOD_ID);
    }

    public static boolean touhouLittleMaidLoaded() {
        return ModList.get().isLoaded(TLM_MOD_ID);
    }
}
