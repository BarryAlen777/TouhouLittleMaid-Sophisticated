package dev.barryalen.maidsophisticated;

import dev.barryalen.maidsophisticated.advancement.FirstMaidBackpackTrigger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** 全部游戏事件挂接：装备、面板、智能存取、持久化、掉落。 */
public final class Events {
    /** 正在跟踪的女仆（弱引用，服务器侧扫描用）。 */
    private static final Set<LivingEntity> TRACKED_MAIDS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<LivingEntity, Boolean>()));

    private static int tickCounter = 0;

    private Events() {}

    /**
     * 是不是车万女仆的女仆实体。
     *
     * 先按注册表命名空间粗筛（没装女仆模组时这里就返回 false，不会去碰她的类），
     * 再用真正的女仆类确认。只按命名空间判断会把同模组的妖精、鬼火之类也当成女仆，
     * 导致给它们挂背包数据、拿背包扣右键妖精也能开面板——所以必须再精确一步。
     */
    public static boolean isMaid(Entity entity) {
        if (!(entity instanceof Mob)) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (key == null || !key.getNamespace().equals(MaidSophisticated.TLM_MOD_ID)) {
            return false;
        }
        return TlmCompat.loaded() && TlmCompat.isMaidEntity(entity);
    }

    /** 主人校验：驯服过的女仆只认主人；野生女仆谁都能操作。 */
    public static boolean isMaidOwner(@Nullable LivingEntity maid, Player player) {
        if (maid instanceof TamableAnimal tamable) {
            var owner = tamable.getOwner();
            return owner == null || owner.getUUID().equals(player.getUUID());
        }
        return true;
    }

    // ---------- capability 挂接与生命周期 ----------

@SubscribeEvent
        public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof LivingEntity living) || !isMaid(living)) {
            return;
        }
        MaidBackpackData data = new MaidBackpackData(living);
        LazyOptional<MaidBackpackData> optional = LazyOptional.of(() -> data);
        event.addCapability(new ResourceLocation(MaidSophisticated.MOD_ID, "maid_backpack"), new ICapabilityProvider() {
            @Nonnull
            @Override
            public <T> LazyOptional<T> getCapability(@Nonnull net.minecraftforge.common.capabilities.Capability<T> cap,
                                                     @Nullable net.minecraft.core.Direction side) {
                return MaidCaps.MAID_BACKPACK.orEmpty(cap, optional);
            }
        });
    }

@SubscribeEvent
        public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!isMaid(event.getEntity())) {
            return;
        }
        ClientMaidIndex.track(event.getEntity());
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        MaidBackpackData.of(event.getEntity()).ifPresent(data -> {
            data.readFromEntity();
            ModNetwork.syncMaidBackpack((LivingEntity) event.getEntity(), data);
            TRACKED_MAIDS.add((LivingEntity) event.getEntity());
        });
        // 老存档兼容：更早的版本把精妙背包写成了女仆自己的"背包类型"，
        // 那种存档里她自己的背包其实已经被顶掉了，进世界时帮她改回空背包。
        if (event.getEntity() instanceof LivingEntity living && TlmCompat.loaded()) {
            TlmCompat.repairForeignBackpackType(living);
        }
    }

    /** 玩家开始追踪女仆时补发背包数据：后靠近的玩家也能立刻看到背上的背包。 */
    @SubscribeEvent
    public static void onStartTracking(net.minecraftforge.event.entity.player.PlayerEvent.StartTracking event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getTarget() instanceof LivingEntity maid && isMaid(maid)) {
            MaidBackpackData.of(maid).ifPresent(data -> ModNetwork.syncMaidBackpackTo(player, maid, data));
        }
    }

@SubscribeEvent
        public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!isMaid(event.getEntity())) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel) {
            MaidBackpackData.of(event.getEntity()).ifPresent(MaidBackpackData::saveToEntity);
        }
        TRACKED_MAIDS.remove(event.getEntity());
        ClientMaidIndex.untrack(event.getEntity());
        MaidBackpackView.clear(event.getEntity().getId());
    }

    /** 退出世界 / 切维度：清掉客户端按实体 id 缓存的背包外观，免得新世界的女仆串用旧数据。 */
    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            MaidBackpackView.clearAll();
        }
    }

    /** 女仆死亡：背包从独立槽里掉出来，内容物跟着背包物品本身走，不会丢。 */
@SubscribeEvent
        public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide || !isMaid(entity)) {
            return;
        }
        MaidBackpackData.of(entity).ifPresent(data -> {
            for (ItemStack stack : data.copyStacks()) {
                if (!stack.isEmpty()) {
                    ItemEntity drop = new ItemEntity(entity.level(), entity.getX(), entity.getY() + 0.3, entity.getZ(), stack);
                    drop.setDefaultPickUpDelay();
                    event.getDrops().add(drop);
                }
            }
            data.clearAndDetach();
        });
    }

    // ---------- 玩家交互 ----------

    /**
     * 右键女仆：
     * - 手持"女仆背包扣"→ 打开女仆 GUI 的精妙背包标签页；
     * - 手持精妙背包（恰好 1 个）→ 直接装备到空槽（最多 3 个背包）。
     */
@SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide || !isMaid(event.getTarget())) {
            return;
        }
        Player player = event.getEntity();
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) {
            return;
        }
        LivingEntity maid = (LivingEntity) event.getTarget();

        if (held.is(net.minecraft.world.item.Items.AIR)) {
            return;
        }

        if (held.getItem() == ModItems.MAID_BACKPACK_BUCKLE.get()) {
            // 非主人不许开别人家女仆的背包；没装精妙背包时也不开（开了也没内容可显示）
            if (!isMaidOwner(maid, player)) {
                return;
            }
            if (player instanceof ServerPlayer serverPlayer && openMaidPanel(serverPlayer, maid)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }

        if (MaidBackpackData.isSophisticatedBackpack(held)) {
            if (!MaidSophisticated.sophisticatedBackpacksLoaded() || held.getCount() != 1) {
                return;
            }
            Optional<MaidBackpackData> optional = MaidBackpackData.of(maid);
            if (optional.isEmpty()) {
                return;
            }
            MaidBackpackData data = optional.get();
            int slot = data.firstEmptySlot();
            if (slot < 0) {
                // 槽位满：不再装备，女仆原有的"送礼"逻辑照常进行
                return;
            }
            ItemStack leftover = data.insertItem(slot, held.copy(), false);
            if (leftover.isEmpty()) {
                held.shrink(1);
                playEquipFeedback(maid);
                creditFirstBackpack(player);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }

    /**
     * 打开女仆 GUI 的"精妙背包"标签页（顶部第四个标签 / 背包扣右键都走这里）。
     *
     * @return 真的打开了才返回 true；缺依赖或不是服务端时返回 false，调用方别吞掉原版交互。
     */
    public static boolean openMaidPanel(ServerPlayer player, LivingEntity maid) {
        if (!(maid.level() instanceof ServerLevel) || !MaidSophisticated.touhouLittleMaidLoaded()
                || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return false;
        }
        return TlmCompat.openTab(player, maid);
    }

    // ---------- 智能存取 ----------

    // 注：Forge 1.20.1 没有生物拾取物品事件（EntityPickupItemEvent 已移除），
    // "拾取先入背包"由下面的服务器扫描实现：女仆附近掉落物优先入包。

    /**
     * 服务器节流的后台维护：
     * - 每 10 tick：女仆附近掉落物按"女仆自己的物品栏 → 女仆自己的背包 → 精妙背包"顺序收取；
     * - 每 40 tick：女仆手上空着时，按同样顺序搜一把趁手工具塞到手上（"取用自动搜包"）。
     */
@SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        boolean pickupPass = tickCounter % 10 == 0;
        boolean refillPass = tickCounter % 40 == 13;
        boolean statePass = tickCounter % 40 == 0;
        if (!pickupPass && !refillPass && !statePass) {
            return;
        }
        if (!TlmCompat.loaded() || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        for (LivingEntity maid : TRACKED_MAIDS) {
            if (maid.isRemoved() || !(maid.level() instanceof ServerLevel level)) {
                continue;
            }
            MaidBackpackData data = MaidBackpackData.of(maid).orElse(null);
            if (data == null) {
                continue;
            }
            // 兜底补发背包状态：女仆刚刷出来时首个同步包可能早于客户端建好实体而丢掉，
            // 每两秒补一次，客户端最多两秒就能把背上的外观纠正过来。
            if (statePass) {
                ModNetwork.syncMaidBackpack(maid, data);
            }
            // 没有精妙背包的女仆不归我们管，交给她自己的 AI
            if (MaidBackpackData.isEmptyHandler(data)) {
                continue;
            }
            // 女仆自己的"拾取"开关关着，就别替她捡东西
            if (pickupPass && TlmCompat.isPickupEnabled(maid)) {
                pickupNearbyItems(level, maid, data);
            }
            if (refillPass) {
                refillMaidTool(maid, data);
            }
        }
    }

    /**
     * 收取附近掉落物，顺序与玩家直觉一致：
     * 1. 女仆自己的物品栏（她装了车万女仆的背包时，背包扩容出来的格子也算自己的）；
     * 2. 女仆自己的背包格（就是上一步里扩容的那些，没背包就没有这一步）；
     * 3. 最后才进精妙背包，按槽位顺序一个个塞。
     */
    private static void pickupNearbyItems(ServerLevel level, LivingEntity maid, MaidBackpackData data) {
        List<net.minecraft.world.entity.Entity> items = level.getEntities(maid, maid.getBoundingBox().inflate(2.2),
                e -> e instanceof ItemEntity item && !item.isRemoved() && !item.hasPickUpDelay());
        for (net.minecraft.world.entity.Entity entity : items) {
            if (!(entity instanceof ItemEntity itemEntity) || itemEntity.isRemoved()) {
                continue;
            }
            ItemStack before = itemEntity.getItem().copy();
            ItemStack remaining = before.copy();
            // 1 + 2：女仆自己的物品栏与她自己背包的格子
            remaining = TlmCompat.insertIntoMaidInventory(maid, remaining);
            // 3：精妙背包
            for (int i = 0; i < MaidBackpackData.SLOTS && !remaining.isEmpty(); i++) {
                ItemStack backpack = data.getStackInSlot(i);
                if (backpack.isEmpty()) {
                    continue;
                }
                remaining = SbCompat.insertIntoBackpack(backpack, remaining);
            }
            if (ItemStack.matches(before, remaining)) {
                continue;
            }
            int taken = before.getCount() - remaining.getCount();
            if (taken > 0) {
                // 和女仆自己弯腰捡一样记一次"拾取"，经验与拾取动画都按原版走
                maid.take(itemEntity, taken);
            }
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(remaining);
            }
        }
    }

    /** 手上空着时补一把工具：先掏女仆自己的背包格，再掏精妙背包。 */
    private static void refillMaidTool(LivingEntity maid, MaidBackpackData data) {
        if (!maid.getMainHandItem().isEmpty()) {
            return;
        }
        ItemStack own = TlmCompat.takeToolFromMaidInventory(maid);
        if (!own.isEmpty()) {
            maid.setItemInHand(InteractionHand.MAIN_HAND, own);
            return;
        }
        for (int i = 0; i < MaidBackpackData.SLOTS; i++) {
            ItemStack backpack = data.getStackInSlot(i);
            if (backpack.isEmpty()) {
                continue;
            }
            ItemStack tool = SbCompat.extractToolForMaid(backpack);
            if (!tool.isEmpty()) {
                maid.setItemInHand(InteractionHand.MAIN_HAND, tool);
                return;
            }
        }
    }

    /** 是不是"趁手的东西"：斧 / 镐 / 锹 / 锄 / 剑 / 盾。拾取与取用规则共用。 */
    public static boolean isToolOrWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var item = stack.getItem();
        return item instanceof net.minecraft.world.item.AxeItem
                || item instanceof net.minecraft.world.item.PickaxeItem
                || item instanceof net.minecraft.world.item.ShovelItem
                || item instanceof net.minecraft.world.item.HoeItem
                || item instanceof net.minecraft.world.item.SwordItem
                || item == net.minecraft.world.item.Items.SHIELD;
    }

    // ---------- 反馈与进度 ----------

    /** 装备成功：8 个光点粒子绕一圈 + 1 声皮革装备音效。 */
    public static void playEquipFeedback(LivingEntity maid) {
        Level level = maid.level();
        if (level instanceof ServerLevel serverLevel) {
            double cx = maid.getX();
            double cy = maid.getY() + 0.9;
            double cz = maid.getZ();
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI / 4 * i;
                serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                        cx + Math.cos(angle) * 0.55, cy, cz + Math.sin(angle) * 0.55,
                        1, 0.0, 0.02, 0.0, 0.01);
            }
            serverLevel.playSound(null, cx, cy, cz, SoundEvents.ARMOR_EQUIP_LEATHER,
                    SoundSource.PLAYERS, 0.7f, 1.0f);
        }
    }

    /** 首次给女仆背上精妙背包 → 弹成就"这就很妙"（原版语义：每个玩家只弹一次，重装不会再弹）。 */
    public static void creditFirstBackpack(@Nullable net.minecraft.world.entity.Entity actor) {
        if (actor instanceof ServerPlayer player && MaidSophisticated.sophisticatedBackpacksLoaded()) {
            FirstMaidBackpackTrigger.INSTANCE.trigger(player);
        }
    }
}
