package dev.barryalen.maidsophisticated.client;

import dev.barryalen.maidsophisticated.MaidSophisticated;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = MaidSophisticated.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModClientSetup {
    private ModClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (MaidSophisticated.touhouLittleMaidLoaded()) {
                TlmClient.init();
            }
        });
    }
}
