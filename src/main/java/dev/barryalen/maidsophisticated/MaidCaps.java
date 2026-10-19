package dev.barryalen.maidsophisticated;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

/** 女仆专属精妙背包槽 capability。 */
public final class MaidCaps {
    public static final Capability<MaidBackpackData> MAID_BACKPACK =
            CapabilityManager.get(new CapabilityToken<>() {});

    private MaidCaps() {}
}
