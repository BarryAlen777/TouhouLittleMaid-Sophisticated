package dev.barryalen.maidsophisticated;

import com.github.tartaricacid.touhoulittlemaid.api.backpack.IMaidBackpack;
import com.github.tartaricacid.touhoulittlemaid.entity.backpack.BackpackManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.barryalen.maidsophisticated.gui.MaidSophTabContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.RegistryObject;

/**
 * 与车万女仆的全部耦合都集中在这个类。
 * 只有在确认 touhou_little_maid 已安装时才会被类加载。
 *
 * 这里只做"读"和"用"女仆自己的东西，绝不在服务端改写她的背包类型：
 * 精妙背包的外观是在客户端渲染那一刻临时把"背包类型"换成我们注册的档位，
 * 让车万女仆自己的背包层去画（站、坐、躺、骑乘、模型缩放全都天生正确），
 * 画完立刻换回去，所以她自己的背包、容量和存档一点没动。
 */
public final class TlmCompat {
    private TlmCompat() {}

    public static boolean loaded() {
        return ModList.get().isLoaded(MaidSophisticated.TLM_MOD_ID);
    }

    /**
     * 精确判断"这是不是女仆"：只有 {@code EntityMaid} 才算。
     * 同模组的妖精、鬼火也带 touhou_little_maid 命名空间，不能一并当成女仆处理。
     */
    public static boolean isMaidEntity(net.minecraft.world.entity.Entity entity) {
        return entity instanceof EntityMaid;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerMenu() {
        ModMenus.MAID_SOPH_TAB = (RegistryObject) ModMenus.MENUS.register("maid_soph_tab",
                () -> IForgeMenuType.create(MaidSophTabContainer::new));
    }

    // ---------- 女仆自己的背包 ----------

    /**
     * 女仆有没有装备她自己的背包（车万女仆的布包 / 小包 / 中包 / 大包 / 熔炉包……）。
     * 女仆的背包在车万女仆里是"背包类型"，不是物品槽，所以直接问类型。
     */
    public static boolean hasOwnBackpack(LivingEntity entity) {
        if (!(entity instanceof EntityMaid maid)) {
            return false;
        }
        // 渲染期间背部外观会把类型临时换成精妙背包，这里要问她"真实"的类型
        IMaidBackpack type = MaidBackpackLook.trueType(maid);
        if (type == null || type.getId() == null) {
            return false;
        }
        return !type.getId().equals(BackpackManager.getEmptyBackpack().getId());
    }

    /** 女仆自带背包对应的物品（只用于界面上画个小图标），没有就返回空。 */
    public static ItemStack ownBackpackItem(LivingEntity entity) {
        if (!(entity instanceof EntityMaid maid) || !hasOwnBackpack(maid)) {
            return ItemStack.EMPTY;
        }
        Item item = MaidBackpackLook.trueType(maid).getItem();
        return item == null || item == Items.AIR ? ItemStack.EMPTY : item.getDefaultInstance();
    }

    /**
     * 老存档兼容：以前的版本把精妙背包注册成了女仆的背包类型并真的装了上去，
     * 那种存档里她自己的背包是被顶掉的。现在精妙背包不占那个位置了，
     * 进世界时把这种"外来类型"改回空背包，玩家可以重新给她装回自己的背包。
     */
    public static void repairForeignBackpackType(LivingEntity entity) {
        if (!(entity instanceof EntityMaid maid) || maid.level().isClientSide) {
            return;
        }
        IMaidBackpack type = maid.getMaidBackpackType();
        if (type != null && type.getId() != null
                && MaidSophisticated.MOD_ID.equals(type.getId().getNamespace())) {
            maid.setMaidBackpackType(BackpackManager.getEmptyBackpack());
        }
    }

    /**
     * 女仆自己开的"拾取"开关：关掉时我们也不替她捡东西。
     * 拾取类型设成"只捡经验"时同样不捡物品，得尊重玩家的设置。
     */
    public static boolean isPickupEnabled(LivingEntity entity) {
        if (!(entity instanceof EntityMaid maid) || !maid.isPickup()) {
            return false;
        }
        return maid.getConfigManager().getPickupType().canPickItem();
    }

    /** 女仆自身物品栏里"额外背包格"的起点（0~5 是装备/手持/展示格，往后的才是背包容量）。 */
    public static int maidStorageStart() {
        return BackpackManager.getEmptyBackpack().getAvailableMaxContainerIndex();
    }

    /**
     * 车万女仆自己的"这个物品女仆能不能捡"规则（配置里的拾取黑名单、不可堆叠物品等）。
     * 我们替她捡东西或往精妙背包里塞之前必须问一下，不然服务端配置的黑名单就形同虚设。
     */
    public static boolean canMaidPickupItem(ItemStack stack) {
        return EntityMaid.canInsertItem(stack);
    }

    /** 坐着的女仆在休息，不该隔着两格把地上的东西吸走（她自己的拾取 AI 也不会这么做）。 */
    public static boolean isSitting(LivingEntity entity) {
        return entity instanceof EntityMaid maid && maid.isMaidInSittingPose();
    }

    /** 用女仆模组自己的、带节流的拾取音效，替她捡东西时听感和原版一致。 */
    public static void playPickupSound(LivingEntity entity) {
        if (entity instanceof EntityMaid maid) {
            maid.tryPlayMaidPickupSound();
        }
    }

    /**
     * 我们替女仆完成了拾取，就补发女仆模组的"拾取完成"事件。
     * 正常拾取这条事件由命中精妙背包的那次 pickupItem 内部发出；我们取消了那次调用，
     * 不补发的话 KubeJS 脚本、任务/成就类模组就永远收不到"女仆捡到东西"。
     */
    public static void postPickupResult(LivingEntity entity, ItemStack stack, int count) {
        if (count <= 0 || !(entity instanceof EntityMaid maid) || stack.isEmpty()) {
            return;
        }
        // 必须是带着完整 NBT 的副本，不能 new ItemStack(item, count)：
        // 那样会把附魔、自定义名、容器内容全丢掉，依赖这条事件的模组拿到的是"同名白板物品"。
        ItemStack payload = stack.copy();
        payload.setCount(count);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent.ItemResultPost(maid, payload));
    }

    /** 女仆自己物品栏的可用格数（没背包时只有 6 格装备位）。 */
    public static int maidStorageEnd(EntityMaid maid) {
        IMaidBackpack type = MaidBackpackLook.trueType(maid);
        return type == null ? maidStorageStart() : type.getAvailableMaxContainerIndex();
    }

    /**
     * 把物品塞进女仆的"可用物品栏"：先填她原有的格子，再填她背包扩容出来的格子。
     *
     * <p>注意：自"一次接通"补丁上线后，{@code getAvailableInv} 的返回值在服务端已经
     * 拼上了精妙背包（顺序：她的格子在前、背包在后）。所以这个方法在装了补丁的
     * 服务端上实际是"先她自己、溢出进精妙背包"两级；名字里的"自己物品栏"要按这个理解。
     * 精妙背包不在场 / 补丁未生效时，就退回只有她自己的格子——两种情况都安全。</p>
     */
    public static ItemStack insertIntoMaidInventory(LivingEntity entity, ItemStack stack) {
        if (stack.isEmpty() || !(entity instanceof EntityMaid maid)) {
            return stack;
        }
        IItemHandler inv = maid.getAvailableInv(false);
        return ItemHandlerHelper.insertItemStacked(inv, stack, false);
    }

    /**
     * 女仆的"可用物品栏"还放得下这堆东西吗（只模拟，不动库存）。
     * 同 {@link #insertIntoMaidInventory}：服务端上这个视图含精妙背包，客户端副本不含。
     */
    public static boolean canInsertIntoMaidInventory(LivingEntity entity, ItemStack stack) {
        if (stack.isEmpty() || !(entity instanceof EntityMaid maid)) {
            return false;
        }
        IItemHandler inv = maid.getAvailableInv(false);
        return ItemHandlerHelper.insertItemStacked(inv, stack.copy(), true).getCount() < stack.getCount();
    }

    /**
     * 从女仆自己的物品栏里取一把工具/武器出来（取用顺序：女仆 → 精妙背包）。
     *
     * 0~5 是她自带的 6 格（拾取时也先填这里），其中第 5 格是"背上展示物"，
     * 取走会改变她的外观，所以跳过那一格；6 以后是她自己背包扩容出来的格子。
     */
    public static ItemStack takeToolFromMaidInventory(LivingEntity entity) {
        if (!(entity instanceof EntityMaid maid)) {
            return ItemStack.EMPTY;
        }
        int end = maidStorageEnd(maid);
        var inv = maid.getMaidInv();
        for (int i = 0; i < end && i < inv.getSlots(); i++) {
            if (i == 5) {
                continue;
            }
            if (Events.isToolOrWeapon(inv.getStackInSlot(i))) {
                return inv.extractItem(i, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    // ---------- 面板 ----------

    /** 打开标签页；缺依赖 / 不是主人时返回 false，调用方不要吞掉原版交互。 */
    public static boolean openTab(ServerPlayer player, LivingEntity maid) {
        if (ModMenus.MAID_SOPH_TAB == null || !MaidSophisticated.sophisticatedBackpacksLoaded()
                || !Events.isMaidOwner(maid, player)) {
            return false;
        }
        // 打开前先把她的精妙背包状态推一份给这个玩家，界面上方的 3D 预览立刻就是对的
        MaidBackpackData.of(maid).ifPresent(data -> ModNetwork.syncMaidBackpackTo(player, maid, data));
        NetworkHooks.openScreen(player,
                new SimpleMenuProvider((windowId, inventory, menuPlayer) ->
                        new MaidSophTabContainer(windowId, inventory, maid.getId()),
                        Component.translatable("gui." + MaidSophisticated.MOD_ID + ".tab")),
                buffer -> buffer.writeInt(maid.getId()));
        return true;
    }

    /**
     * 服务端收包时的统一校验：必须是玩家当前打开的标签页，而且主人 / 距离仍然有效。
     * 光靠 {@code stillValid} 每 tick 关窗口不够——恶意客户端能抢在关窗口前把包发上来。
     */
    @javax.annotation.Nullable
    private static MaidSophTabContainer validTab(Player player) {
        if (player.containerMenu instanceof MaidSophTabContainer container && container.stillValid(player)) {
            return container;
        }
        return null;
    }

    public static void handleSelect(Player player, int index) {
        MaidSophTabContainer container = validTab(player);
        if (container != null) {
            container.serverSelect(index);
        }
    }

    public static void handleScroll(Player player, int row) {
        MaidSophTabContainer container = validTab(player);
        if (container != null) {
            container.serverScroll(row);
        }
    }

    public static void handleSearch(Player player, String text) {
        MaidSophTabContainer container = validTab(player);
        if (container != null) {
            container.serverSearch(text);
        }
    }

    public static void handleSort(Player player, int mode) {
        MaidSophTabContainer container = validTab(player);
        if (container != null) {
            container.serverSort(mode);
        }
    }

    public static void handleToggleMainBackpack(Player player) {
        MaidSophTabContainer container = validTab(player);
        if (container != null && container.backpackData() != null) {
            container.serverToggleShowSophisticated();
        }
    }

    public static void handleOpenNative(ServerPlayer player) {
        MaidSophTabContainer container = validTab(player);
        if (container != null && container.getMaid() != null && !container.getMaid().isRemoved()) {
            SbCompat.openBackpackGui(player, container.getMaid(), container.selectedSlot());
        }
    }

    public static MenuType<?> menuType() {
        return ModMenus.MAID_SOPH_TAB == null ? null : ModMenus.MAID_SOPH_TAB.get();
    }

    public static AbstractContainerMenu asTab(Player player) {
        return player.containerMenu instanceof MaidSophTabContainer c ? c : null;
    }
}
