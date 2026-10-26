package dev.barryalen.maidsophisticated.backpack;

import com.github.tartaricacid.touhoulittlemaid.api.backpack.IMaidBackpack;
import com.github.tartaricacid.touhoulittlemaid.entity.backpack.BackpackManager;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.barryalen.maidsophisticated.MaidBackpackData;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import dev.barryalen.maidsophisticated.ModItems;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * 车万女仆"背包类型"体系里的自定义类型：精妙背包。
 *
 * <p>渲染时把客户端背包类型临时换成这个档位，让 TLM 自己的
 * {@code LayerMaidBackpack} / {@code GeckoLayerMaidBackpack} 调用
 * {@link MaidBackpackItemModel}，在原版背包的挂点和姿势上画物品模型。</p>
 */
public final class SophMaidBackpack extends IMaidBackpack {
    public static final SophMaidBackpack LEATHER = new SophMaidBackpack("leather", "backpack");
    public static final SophMaidBackpack COPPER = new SophMaidBackpack("copper", "copper_backpack");
    public static final SophMaidBackpack IRON = new SophMaidBackpack("iron", "iron_backpack");
    public static final SophMaidBackpack GOLD = new SophMaidBackpack("gold", "gold_backpack");
    public static final SophMaidBackpack DIAMOND = new SophMaidBackpack("diamond", "diamond_backpack");
    public static final SophMaidBackpack NETHERITE = new SophMaidBackpack("netherite", "netherite_backpack");

    public static final SophMaidBackpack[] ALL = {LEATHER, COPPER, IRON, GOLD, DIAMOND, NETHERITE};

    private static final Map<String, SophMaidBackpack> BY_ITEM_PATH = new HashMap<>();

    /**
     * 六个档位共用同一个物品模型绘制器。
     *
     * 注意必须懒加载：{@code MaidBackpackItemModel} 继承客户端专用的
     * {@code EntityModel}，而这个类在服务端（{@code addMaidBackpack} 注册时）也会被
     * 加载——静态字段写成 {@code = new ...} 会让专用服务端直接 NoClassDefFoundError。
     */
    private static EntityModel<EntityMaid> itemModel;

    static {
        for (SophMaidBackpack backpack : ALL) {
            BY_ITEM_PATH.put(backpack.itemPath, backpack);
        }
    }

    /** 按物品注册名挑对应档位；认不出来就按布包算。 */
    public static SophMaidBackpack forStack(ItemStack stack) {
        if (!stack.isEmpty()) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null) {
                SophMaidBackpack backpack = BY_ITEM_PATH.get(key.getPath());
                if (backpack != null) {
                    return backpack;
                }
            }
        }
        return LEATHER;
    }

    public static boolean isSophType(IMaidBackpack type) {
        return type instanceof SophMaidBackpack;
    }

    private final ResourceLocation id;
    private final ResourceLocation texture;
    private final String itemPath;

    private SophMaidBackpack(String tier, String itemPath) {
        this.id = new ResourceLocation(MaidSophisticated.MOD_ID, "sophisticated_backpack_" + tier);
        this.texture = new ResourceLocation(MaidSophisticated.MOD_ID,
                "textures/entity/backpack/" + tier + ".png");
        this.itemPath = itemPath;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    /**
     * 返回一个生存里正常途径拿不到的技术占位物品，而不是真正的背包扣。
     *
     * 车万女仆会按 getItem() 建立"物品 → 背包类型"映射：谁拿着映射里的物品右键女仆，
     * 她的换背包流程就会把女仆的背包类型改成这一档。背包扣一旦进这张表，
     * "拿背包扣右键女仆开面板"就会顺带把她的客户端背包类型永久改掉
     * （换包逻辑是交互预测，客户端也会执行，而我们的服务端处理会取消那次交互）。
     * 占位物品正常途径拿不到，但 /give 之类仍能命中映射，所以拿它右键女仆的换包
     * 路径在 {@code Events}（玩家交互事件，两侧都拦）和 {@code PickupEvents}
     * （{@code InteractMaidEvent} 兜底）里各被拦掉一道。
     */
    @Override
    public Item getItem() {
        return ModItems.MAID_BACKPACK_MARKER.get();
    }

    @Override
    public void onPutOn(ItemStack stack, Player player, EntityMaid maid) {
        // 万一被别的途径装上了：立刻退回去，别让她的真实背包类型被顶掉
        if (maid != null && !maid.level().isClientSide) {
            maid.setMaidBackpackType(BackpackManager.getEmptyBackpack());
        }
    }

    /** 用剪刀从背上取下时，把专属槽里最外面那个精妙背包还给玩家。 */
    @Override
    public ItemStack getTakeOffItemStack(ItemStack stack, Player player, EntityMaid maid) {
        if (player == null || maid == null) {
            return ItemStack.EMPTY;
        }
        return MaidBackpackData.of(maid).map(MaidBackpackData::popTopBackpack).orElse(ItemStack.EMPTY);
    }

    @Override
    public void onTakeOff(ItemStack stack, Player player, EntityMaid maid) {
        // 精妙背包的进出全部由自己的 3 个专属槽管，这里没有额外要做的事
    }

    @Override
    public void onSpawnTombstone(EntityMaid maid, EntityTombstone tombstone) {
        // 女仆死亡时背包由 Events.onLivingDrops 掉出来
    }

    /** 空手右键仍然打开女仆原本的主界面，不抢那个入口。 */
    @Override
    public MenuProvider getGuiProvider(int entityId) {
        return BackpackManager.getEmptyBackpack().getGuiProvider(entityId);
    }

    /** 不扩展女仆自身物品栏的容量：多出来的位置全部在精妙背包自己的格子里。 */
    @Override
    public int getAvailableMaxContainerIndex() {
        return 6;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void offsetBackpackItem(PoseStack poseStack) {
        poseStack.translate(0.0, 0.25, -0.25);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public EntityModel<EntityMaid> getBackpackModel(EntityModelSet modelSet) {
        if (itemModel == null) {
            itemModel = new MaidBackpackItemModel();
        }
        return itemModel;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public ResourceLocation getBackpackTexture() {
        return texture;
    }
}
