package dev.barryalen.maidsophisticated;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 模组自带物品：一把"背包扣"，用来打开女仆的精妙背包面板。 */
public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = MaidSophisticated.ITEMS;

    /** 女仆背包扣：拿在手里右键女仆，打开女仆的精妙背包面板。 */
    public static final RegistryObject<Item> MAID_BACKPACK_BUCKLE =
            ITEMS.register("maid_backpack_buckle", () -> new MaidBackpackBuckleItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}

    public static void init() {
        // no-op：引用本类即会触发静态字段注册
    }
}
