package dev.barryalen.maidsophisticated.client;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.api.event.client.RenderMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.barryalen.maidsophisticated.MaidBackpackData;
import dev.barryalen.maidsophisticated.MaidBackpackLook;
import dev.barryalen.maidsophisticated.MaidBackpackView;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import dev.barryalen.maidsophisticated.ModNetwork;
import dev.barryalen.maidsophisticated.TlmCompat;
import dev.barryalen.maidsophisticated.backpack.SophMaidBackpack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 女仆背上的精妙背包外观——方案 A：全部交给车万女仆自己的背包层来画。
 *
 * <p>车万女仆背上那个背包只由她自己的两条渲染层决定（普通人形走 {@code LayerMaidBackpack}，
 * 骨骼模型走 {@code GeckoLayerMaidBackpack}），而这两层只认
 * {@code maid.getMaidBackpackType()} 这个注册类型。它们已经处理好了站、坐、躺、骑乘、
 * 走路前倾、模型整体缩放、自定义模型自带的背包定位骨骼——所以最稳的做法不是自己拿物品模型
 * 去贴坐标（那样姿势和位置全得靠猜，前面几版就是这么翻车的），而是：
 * <b>渲染前把"她的背包类型"临时换成我们注册的六档之一，让她自己的层去画。</b></p>
 *
 * <p>我们注册的六档（{@link SophMaidBackpack}）共用同一个"模型"：{@code MaidBackpackItemModel}，
 * 它不画任何方块几何，只在这一帧的挂点姿势上调用物品渲染器画精妙背包自己的物品模型——
 * 位置和姿势全部来自车万女仆的背包层，所以站、坐、躺、骑乘、缩放都跟着女仆模型走，
 * 外观和玩家背上的精妙背包完全一致（连升级部件的贴花都一致）。
 * 代价是要在渲染那一刻借道她的"背包类型"，帧末立刻还原。</p>
 *
 * <p>只在客户端内存里换类型，帧末立刻换回去；服务端数据、她自己的存档、
 * 背包容量和物品栏一点没动，两种背包可以同时装。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MaidBackpackRenderer {
    /** 这一帧已经换过背包类型的女仆，帧末统一还原。 */
    private static final Set<Integer> SWAPPED = new HashSet<>();
    /** 客户端主动要状态包的节流（每只女仆最快 2 秒一次）。 */
    private static final Map<Integer, Long> LAST_REQUEST = new HashMap<>();
    /** 只打一次诊断日志。 */
    private static boolean loggedDecision;
    /** TLM 当前正在画的女仆；背包物品模型用它来取 ItemStack。 */
    private static EntityMaid CURRENT_MAID;

    private MaidBackpackRenderer() {}

    public static EntityMaid currentMaid() {
        return CURRENT_MAID;
    }

    @SubscribeEvent
    public static void onRenderMaid(RenderMaidEvent event) {
        EntityMaid maid = asMaid(event.getMaid());
        if (maid == null) {
            return;
        }
        CURRENT_MAID = maid;
        applyTo(maid);
    }

    /**
     * GUI 预览只补上下文/换类型，不自己画背包；同时把"这一次渲染用的批次"压进栈里。
     *
     * <p>Forge 在 {@code LivingEntityRenderer.render} 的最开头抛 Pre、最末尾抛 Post，
     * 背包层（我们画精妙背包物品的地方）正好落在这两者之间，而事件里带的
     * {@code MultiBufferSource} 就是调用方那一份：世界里是全局实体批次，
     * 界面 3D 预览（车万女仆主界面、我们的页签、模型详情、tooltip 等，
     * 原版都走 {@code InventoryScreen.renderEntityInInventory}）是界面自己的
     * {@code GuiGraphics.bufferSource()}，画完由调用方统一 flush。
     * 所以接住事件给的批次就能跟女仆身体同进同出。</p>
     *
     * <p>车万女仆的 Gecko/YSM 分支也走这条路：它的渲染器在 Pre 里面再发起一次渲染，
     * 但把收到的 {@code bufferSource} 原样传给了每一个 layer（包括背包层），
     * 所以这里压的这份就是层真正用的那份，不需要反射它的内部字段。</p>
     */
    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (event.getEntity() instanceof EntityMaid maid) {
            CURRENT_MAID = maid;
            applyTo(maid);
            SophBackpackRender.pushBuffers(event.getMultiBufferSource());
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (event.getEntity() instanceof EntityMaid) {
            // 只弹自己压的那一层：嵌套渲染（层里再画一个实体）内层先弹回外层，
            // 外层的批次不会在这里被提前清掉。
            SophBackpackRender.popBuffers();
            if (CURRENT_MAID != null && CURRENT_MAID == event.getEntity()) {
                CURRENT_MAID = null;
            }
        }
    }

    /** 帧首兜底换好、帧末（世界 + 界面都画完）全部还原。 */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            applyForWholeFrame();
        } else {
            restoreAll();
        }
    }

    /** 退出世界 / 切维度：临时状态按实体 id 存着，必须清掉，免得串到下一个世界。 */
    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            reset();
        }
    }

    /** 女仆离场就把她的"补发状态包"节流记录丢掉：长局里这张表只进不出的小毛病顺手修掉。 */
    @SubscribeEvent
    public static void onEntityLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            LAST_REQUEST.remove(event.getEntity().getId());
        }
    }

    // ---------- 换类型 ----------

    /** 把某只女仆这一帧的背包类型换成精妙背包（该不该换、换成哪一档都在这里判）。 */
    private static void applyTo(EntityMaid maid) {
        if (!maid.level().isClientSide || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        // 玩家在女仆设置里关掉了"显示背包"：她自己的层不会画，我们也不该塞一个进去
        if (!maid.getConfigManager().isShowBackpack() || maid.isInvisible()) {
            return;
        }
        if (SophMaidBackpack.isSophType(maid.getMaidBackpackType())) {
            // 已经换过了（渲染事件 + 帧首兜底会各来一次）
            return;
        }

        ItemStack top = topBackpack(maid);
        if (top.isEmpty()) {
            // 数据没到：请服务端补发一次（节流），这一帧保持她原本的样子
            requestState(maid);
            return;
        }
        if (!shouldShowSophisticated(maid)) {
            // 她自己的背包优先（面板里"主背包显示"选了女仆背包）
            return;
        }

        // 记下她真正的类型，别的地方（界面按钮等）还要问
        MaidBackpackLook.record(maid, maid.getMaidBackpackType());
        maid.setMaidBackpackType(SophMaidBackpack.forStack(top));
        SWAPPED.add(maid.getId());

        if (!loggedDecision) {
            loggedDecision = true;
            MaidSophisticated.LOGGER.info(
                    "Maid backpack appearance -> sophisticated: {} on maid {}",
                    top.getItem(), maid.getId());
        }
    }

    /**
     * 帧首兜底：把世界里所有该显示精妙背包的女仆先换好。
     *
     * <p>正常情况下 {@link #onRenderMaid} 已经够用；这里保留是因为背包数据可能晚一帧才到，
     * 帧首先扫一遍能让"背包刚装上"那一帧就显示出来。</p>
     */
    private static void applyForWholeFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !MaidSophisticated.sophisticatedBackpacksLoaded()) {
            return;
        }
        ClientLevel level = mc.level;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof EntityMaid maid) {
                applyTo(maid);
            }
        }
    }

    // ---------- 状态判定 ----------

    /**
     * 背上该不该显示精妙背包：
     * 女仆没装她自己的背包 → 一律显示精妙背包；
     * 两种都有 → 听面板里"主背包显示"那个开关。
     */
    private static boolean shouldShowSophisticated(EntityMaid maid) {
        MaidBackpackView.Entry view = MaidBackpackView.get(maid.getId());
        boolean showFlag;
        if (view != null) {
            showFlag = view.showSophisticated();
        } else {
            MaidBackpackData data = MaidBackpackData.of(maid).orElse(null);
            if (data == null) {
                return !TlmCompat.hasOwnBackpack(maid);
            }
            showFlag = data.isShowSophisticated();
        }
        return !TlmCompat.hasOwnBackpack(maid) || showFlag;
    }

    /** 这一帧该显示的精妙背包（最靠近后背的那个槽）。 */
    private static ItemStack topBackpack(EntityMaid maid) {
        MaidBackpackView.Entry view = MaidBackpackView.get(maid.getId());
        if (view != null) {
            return view.top();
        }
        return MaidBackpackData.of(maid).map(MaidBackpackData::topBackpack).orElse(ItemStack.EMPTY);
    }

    public static ItemStack backpackToShow(EntityMaid maid) {
        if (maid == null || !maid.level().isClientSide
                || !MaidSophisticated.sophisticatedBackpacksLoaded()
                || maid.isInvisible() || !maid.getConfigManager().isShowBackpack()) {
            return ItemStack.EMPTY;
        }
        ItemStack top = topBackpack(maid);
        if (top.isEmpty() || !shouldShowSophisticated(maid)) {
            return ItemStack.EMPTY;
        }
        return top;
    }

    /** 客户端还没拿到这只女仆的背包状态时，主动请服务端补一份（每只女仆最快 2 秒一次）。 */
    private static void requestState(EntityMaid maid) {
        if (MaidBackpackView.get(maid.getId()) != null) {
            return;
        }
        long now = Util.getMillis();
        Long last = LAST_REQUEST.get(maid.getId());
        if (last != null && now - last < 2000L) {
            return;
        }
        LAST_REQUEST.put(maid.getId(), now);
        ModNetwork.sendRequestMaidState(maid.getId());
    }

    // ---------- 还原 ----------

    /** 帧末兜底：万一某一帧因为异常没走到还原，这里把背包类型补回去。 */
    private static void restoreAll() {
        CURRENT_MAID = null;
        // 渲染被别的模组在 Pre 之后取消时 Post 不会来，这里兜底把批次栈清干净
        SophBackpackRender.clearBuffers();
        if (!SWAPPED.isEmpty()) {
            for (Integer id : SWAPPED.toArray(new Integer[0])) {
                EntityMaid maid = findMaid(id);
                if (maid != null) {
                    MaidBackpackLook.restore(maid);
                }
            }
            SWAPPED.clear();
        }
        // 有些路径（比如实体忽然被卸载）没机会走到上面的循环，这里再扫一遍
        MaidBackpackLook.restoreAll();
    }

    private static EntityMaid findMaid(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        return mc.level.getEntity(entityId) instanceof EntityMaid maid ? maid : null;
    }

    private static EntityMaid asMaid(IMaid iMaid) {
        if (iMaid == null) {
            return null;
        }
        return iMaid.asEntity() instanceof EntityMaid maid ? maid : null;
    }

    /** 世界卸载 / 切维度时清干净，别把上一局的 id 带进来。 */
    public static void reset() {
        SWAPPED.clear();
        LAST_REQUEST.clear();
        CURRENT_MAID = null;
        MaidBackpackLook.restoreAll();
    }
}
