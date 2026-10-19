package dev.barryalen.maidsophisticated;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 把"女仆背包扣"放进原版"建筑材料"创造标签页。 */
@Mod.EventBusSubscriber(modid = MaidSophisticated.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModCreativeTab {
    private ModCreativeTab() {}

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModItems.MAID_BACKPACK_BUCKLE);
        }
    }
}
