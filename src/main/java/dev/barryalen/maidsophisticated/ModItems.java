package dev.barryalen.maidsophisticated;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 模组自带物品：一把"背包扣"（打开面板用）+ 一个技术上才可见的占位物品。 */
public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = MaidSophisticated.ITEMS;

    /** 女仆背包扣：拿在手里右键女仆，打开女仆的精妙背包面板。 */
    public static final RegistryObject<Item> MAID_BACKPACK_BUCKLE =
            ITEMS.register("maid_backpack_buckle", () -> new MaidBackpackBuckleItem(new Item.Properties().stacksTo(1)));

    /**
     * 技术占位物品：只给 {@code SophMaidBackpack.getItem()} 用。
     *
     * 车万女仆会按每个背包类型的 getItem() 建立"物品 → 背包类型"映射，玩家拿着
     * 映射里的物品右键女仆就会触发她的换背包流程（客户端也会预测一遍）。背包扣
     * 绝不能进这张表，否则"拿背包扣右键女仆"会被当成"给她换某一档背包"。
     * 这个占位物品生存里正常途径拿不到（无配方、不进创造标签页），但 /give 或
     * 创造"满遍"标签页仍可能拿到并命中映射，所以换包路径在 {@code Events}（玩家
     * 交互事件）和 {@code PickupEvents}（{@code InteractMaidEvent} 兜底）里各拦一道。
     * 注意不能改用 {@code Items.AIR}：空手右键时物品就是 AIR，反而会把空手交互劫持成换背包。
     */
    public static final RegistryObject<Item> MAID_BACKPACK_MARKER =
            ITEMS.register("maid_backpack_marker", () -> new Item(new Item.Properties()));

    private ModItems() {}

    public static void init() {
        // no-op：引用本类即会触发静态字段注册
    }
}
