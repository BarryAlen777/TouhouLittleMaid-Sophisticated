package dev.barryalen.maidsophisticated.backpack;

import com.github.tartaricacid.touhoulittlemaid.api.backpack.IMaidBackpack;
import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;
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

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 车万女仆"背包类型"体系里的自定义类型：精妙背包。
 *
 * 为什么用这套：女仆背上的背包只由两条渲染层画（普通人形模型走 LayerMaidBackpack，
 * 另一种骨骼模型走 GeckoLayerMaidBackpack），它们都只认
 * {@code maid.getMaidBackpackType()} 这个注册类型，按 id 取"模型 + 贴图"贴到背上。
 * 也就是说：想让她背上出现什么，就得让"她当前的背包类型"变成我们注册的类型。
 *
 * 所以现在的做法是——渲染那一刻（{@code RenderMaidEvent}）把客户端的背包类型临时换成
 * 精妙背包对应的档位，渲染完立刻换回去。服务端的数据一点没动，她自己背包的容量、
 * 物品栏、存档全都还是原来的，两种背包可以同时装。
 */
public final class SophMaidBackpack extends IMaidBackpack {
    /**
     * 模型文件放在本模组自己的资源里，坐标系和车万女仆自带的大背包完全一致
     * （1 单位 = 1/16 格、y 往上、+z 朝女仆后背外侧），所以位置分毫不差。
     * 由 tools/gen_backpack_model.py 生成。
     */
    private static final String MODEL_PATH = "/assets/" + MaidSophisticated.MOD_ID
            + "/models/entity/backpack/soph_backpack.json";

    public static final SophMaidBackpack LEATHER = new SophMaidBackpack("leather", "backpack");
    public static final SophMaidBackpack COPPER = new SophMaidBackpack("copper", "copper_backpack");
    public static final SophMaidBackpack IRON = new SophMaidBackpack("iron", "iron_backpack");
    public static final SophMaidBackpack GOLD = new SophMaidBackpack("gold", "gold_backpack");
    public static final SophMaidBackpack DIAMOND = new SophMaidBackpack("diamond", "diamond_backpack");
    public static final SophMaidBackpack NETHERITE = new SophMaidBackpack("netherite", "netherite_backpack");

    public static final SophMaidBackpack[] ALL = {LEATHER, COPPER, IRON, GOLD, DIAMOND, NETHERITE};

    private static final Map<String, SophMaidBackpack> BY_ITEM_PATH = new HashMap<>();

    /** 六个档位共用同一份几何，只是贴图不同。 */
    private static EntityModel<EntityMaid> sharedModel;
    private static boolean modelLoadTried;

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
     * 用背包扣占位：这样车万女仆"右键直接换背包"的通用流程不会把精妙背包
     * 装成女仆自己的背包类型（那个流程会挤掉她原本的背包，是我们不要的）。
     */
    @Override
    public Item getItem() {
        return ModItems.MAID_BACKPACK_BUCKLE.get();
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
        if (!modelLoadTried) {
            modelLoadTried = true;
            try (InputStream stream = SophMaidBackpack.class.getResourceAsStream(MODEL_PATH)) {
                if (stream == null) {
                    MaidSophisticated.LOGGER.error("Maid backpack model resource is missing: {}", MODEL_PATH);
                } else {
                    sharedModel = new SimpleBedrockModel<>(stream);
                }
            } catch (Exception | LinkageError e) {
                // 以前这里静默吞异常，导致背上永远不显示还查不出来；现在打进日志
                MaidSophisticated.LOGGER.error("Failed to build maid sophisticated backpack model", e);
            }
        }
        return sharedModel;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public ResourceLocation getBackpackTexture() {
        return texture;
    }
}
