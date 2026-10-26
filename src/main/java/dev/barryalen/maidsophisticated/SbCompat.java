package dev.barryalen.maidsophisticated;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.network.NetworkHooks;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.inventory.ITrackedContentsItemHandler;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 与精妙背包模组的全部耦合都集中在这个类。
 * 只有在确认 sophisticatedbackpacks 已安装时才会被类加载，避免 NoClassDefFoundError。
 *
 * 打开界面的方式与精妙背包自己（右键手持背包 / curios 槽点击）完全一致：
 * 构造 BackpackContext.Item(处理器名, 标识符, 槽位) 后走 NetworkHooks.openScreen，
 * 因此物品页、升级页双页签、筛选、嵌套背包等所有原生功能直接可用。
 */
public final class SbCompat {
    /** 注册到精妙背包 PlayerInventoryProvider 的处理器名。 */
    public static final String HANDLER_NAME = "maid_backpack";

    private SbCompat() {}

    /** 注册"女仆背包槽"处理器：identifier 就是女仆实体的 UUID 字符串。 */
    public static void registerProvider() {
        PlayerInventoryProvider.get().addPlayerInventoryHandler(
                HANDLER_NAME,
                player -> Set.of(),                       // 不参与玩家自身界面/渲染的遍历
                (player, identifier) -> MaidBackpackData.SLOTS,
                (player, identifier, slot) -> stackOfMaidSlot(player, identifier, slot),
                false,                                    // visibleInGui：不在玩家界面显示
                false,                                    // rendered：不由精妙背包渲染
                false,                                    // ownRenderer
                false                                     // accessibleByAnotherPlayer
        );
    }

    private static ItemStack stackOfMaidSlot(net.minecraft.world.entity.player.Player viewer, String identifier, int slot) {
        if (slot < 0 || slot >= MaidBackpackData.SLOTS) {
            return ItemStack.EMPTY;
        }
        try {
            UUID maidId = UUID.fromString(identifier);
            LivingEntity maid = ClientMaidIndex.resolve(viewer.level(), maidId);
            if (maid == null) {
                return ItemStack.EMPTY;
            }
            // 服务端再兜一层：主人 / 同维度 / 8 格以内，别让走远或别人家的女仆背包还能被读
            if (viewer instanceof ServerPlayer serverPlayer
                    && (maid.level() != serverPlayer.level()
                    || !Events.isMaidOwner(maid, serverPlayer)
                    || maid.distanceToSqr(serverPlayer) > 64.0)) {
                return ItemStack.EMPTY;
            }
            return MaidBackpackData.of(maid).map(data -> data.getStackInSlot(slot)).orElse(ItemStack.EMPTY);
        } catch (IllegalArgumentException ex) {
            return ItemStack.EMPTY;
        }
    }

    /** 用精妙背包的原生界面打开女仆身上第 slot 格背包。 */
    public static void openBackpackGui(ServerPlayer player, LivingEntity maid, int slot) {
        if (slot < 0 || slot >= MaidBackpackData.SLOTS) {
            return;
        }
        // 兜底再校验一次（调用方已经查过）：原生界面一旦打开就能直接改背包内容，
        // 权限/距离/维度任何一项不满足都不该走到这里。
        if (maid.isRemoved() || maid.level() != player.level() || !Events.isMaidOwner(maid, player)
                || maid.distanceToSqr(player) > 64.0) {
            return;
        }
        ItemStack stack = MaidBackpackData.of(maid)
                .map(data -> data.getStackInSlot(slot)).orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) {
            return;
        }
        BackpackContext.Item context = new BackpackContext.Item(HANDLER_NAME, maid.getUUID().toString(), slot, false);
        NetworkHooks.openScreen(player,
                new SimpleMenuProvider((windowId, playerInventory, menuPlayer) ->
                        new BackpackContainer(windowId, menuPlayer, context), context.getDisplayName(player)),
                buffer -> context.toBuffer(buffer, player));
        // 原生界面构造时会懒生成 contentsUuid 等物品 NBT；立刻把快照刷新一次，
        // 免得实体保存时用的还是没有 uuid 的旧物品数据（重载后背包内容会"消失"）。
        MaidBackpackData.of(maid).ifPresent(MaidBackpackData::saveToEntity);
    }

    /** 往背包物品堆里插入物品（走精妙背包对外暴露的 ITEM_HANDLER 能力，尊重背包筛选）。 */
    public static ItemStack insertIntoBackpack(ItemStack backpackStack, ItemStack toInsert) {
        if (toInsert.isEmpty() || backpackStack.isEmpty()) {
            return toInsert;
        }
        return inventoryHandler(backpackStack).map(handler -> insertIntoAnySlot(handler, toInsert, false))
                .orElse(toInsert);
    }

    /**
     * "取用自动搜包"：从背包里搜出第一件合适的工具/武器（供女仆拿在手上干活）。
     * 走升级处理视图，避开外部过滤器的限制。
     */
    public static ItemStack extractToolForMaid(ItemStack backpackStack) {
        if (backpackStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        LazyOptional<IBackpackWrapper> wrapper = backpackStack.getCapability(
                net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance());
        return wrapper.map(w -> {
            ITrackedContentsItemHandler inventory = w.getInventoryHandler();
            for (int i = 0; i < inventory.getSlots(); i++) {
                ItemStack found = inventory.getStackInSlot(i);
                if (Events.isToolOrWeapon(found)) {
                    return inventory.extractItem(i, 1, false);
                }
            }
            return ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY);
    }

    /** 拿当前背包内部物品栏（升级处理后的视图），空包返回 empty。 */
    public static Optional<net.minecraftforge.items.IItemHandlerModifiable> inventoryHandler(ItemStack backpackStack) {
        if (backpackStack.isEmpty()) {
            return Optional.empty();
        }
        return backpackStack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance())
                .map(IBackpackWrapper::getInventoryHandler)
                .map(h -> (net.minecraftforge.items.IItemHandlerModifiable) h);
    }

    /**
     * 把 toInsert 尽量塞进背包内部任意空槽（尊重筛选），返回没塞进去的剩余部分。
     *
     * 注意：这里必须一直操作 {@code toInsert.copy()}，绝不能一边 insert 一边改传入的那个
     * ItemStack。Forge 的 ItemStackHandler 在"空槽且整堆都能放下"时会把传入的那个对象
     * 直接存进槽位，之后再 setCount(0) 就等于把槽里的东西也清空了——上一版
     * shift+左键物品直接消失就是这么来的。
     */
    private static ItemStack insertIntoAnySlot(net.minecraftforge.items.IItemHandlerModifiable handler,
                                               ItemStack toInsert, boolean simulate) {
        ItemStack remaining = toInsert.copy();
        for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
            remaining = handler.insertItem(i, remaining, simulate);
        }
        return remaining;
    }

    /** 把 toInsert 尽量塞进背包内部任意空槽（尊重筛选），改动 in-place。返回是否有任何物品被放入。 */
    public static boolean insertAnywhere(ItemStack backpackStack, ItemStack toInsert) {
        if (toInsert.isEmpty() || backpackStack.isEmpty()) {
            return false;
        }
        return inventoryHandler(backpackStack).map(handler -> {
            ItemStack remaining = insertIntoAnySlot(handler, toInsert, false);
            int inserted = toInsert.getCount() - remaining.getCount();
            if (inserted <= 0) {
                return false;
            }
            toInsert.setCount(remaining.getCount());
            return true;
        }).orElse(false);
    }

    /** 背包里还有没有位置放下这一整堆（只做模拟，不改任何东西）。 */
    public static boolean canAccept(ItemStack backpackStack, ItemStack stack) {
        if (stack.isEmpty() || backpackStack.isEmpty()) {
            return false;
        }
        return inventoryHandler(backpackStack)
                .map(handler -> insertIntoAnySlot(handler, stack, true).getCount() < stack.getCount())
                .orElse(false);
    }
}
