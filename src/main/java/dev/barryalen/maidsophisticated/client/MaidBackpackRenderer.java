package dev.barryalen.maidsophisticated.client;

import com.github.tartaricacid.touhoulittlemaid.api.backpack.IMaidBackpack;
import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.api.event.client.RenderMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.barryalen.maidsophisticated.MaidBackpackData;
import dev.barryalen.maidsophisticated.MaidBackpackLook;
import dev.barryalen.maidsophisticated.MaidBackpackView;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import dev.barryalen.maidsophisticated.ModNetwork;
import dev.barryalen.maidsophisticated.TlmCompat;
import dev.barryalen.maidsophisticated.backpack.SophMaidBackpack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.p3pp3rf1y.sophisticatedbackpacks.client.ClientEventHandler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 女仆背上的精妙背包外观。
 *
 * 画法和玩家背上完全一样：直接拿"精妙背包这个物品"的物品模型，
 * 用精妙背包自己的 WORN 姿势贴到女仆后背，所以等级颜色、前袋、侧袋、电池、水罐
 * 这些升级外观都和玩家身上的分毫不差（这是第 9 轮验证过、看起来最对的那一套）。
 *
 * 两个环节：
 * 1. {@link RenderMaidEvent}：女仆渲染器开始画之前，决定"这一帧背上该显示哪一个"，
 *    要显示精妙背包时，把女仆模组自己的"显示背包"开关临时关掉，她的蓝色背包就不画了；
 * 2. {@link RenderLivingEvent.Pre}：拿到现成的 PoseStack / MultiBufferSource / 光照，
 *    把精妙背包物品模型画在后背上。同一帧结束时在 Post 里把开关还原。
 *
 * 数据来源优先用 {@link MaidBackpackView}（网络包写进来的纯客户端缓存），
 * 拿不到才退回实体 capability——这样同步包来早来晚都不会让外观变成空的。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidBackpackRenderer {
    /** 背包挂在身高百分之多少的位置（大约后背中上方）。 */
    private static final double BACK_HEIGHT_RATIO = 0.52D;
    /** 背包离身体中线往后多少格（按 1.4 格高的女仆算）。 */
    private static final double BACK_DEPTH = 0.27D;
    /** 背包缩放（按 1.4 格高的女仆算），比玩家略小一点。 */
    private static final float BACK_SCALE = 0.78F;
    private static final float BASE_HEIGHT = 1.4F;

    /** 这一帧被临时关掉"显示背包"的女仆 → 她原本的开关值，Post / 帧末还原。 */
    private static final Map<Integer, Boolean> HIDDEN = new HashMap<>();
    /** 这一帧改用了"换背包类型"方式的女仆（骨骼模型 / YSM 模型），Pre 里别重复画。 */
    private static final Set<Integer> SWAPPED = new HashSet<>();
    /** 客户端主动要状态包的节流（每只女仆最快 2 秒一次）。 */
    private static final Map<Integer, Long> LAST_REQUEST = new HashMap<>();
    /** 只打一次诊断日志，方便出问题时从 latest.log 一眼看出卡在哪一步。 */
    private static boolean loggedDecision;

    private MaidBackpackRenderer() {}

    // ---------- 骨骼 / YSM 模型：退回车万女仆自己的背包类型管线 ----------

    /**
     * 只有骨骼模型（gecko）和 YSM 模型才用这条路：它们的渲染器绕过原版
     * {@code LivingEntityRenderer}，{@code RenderLivingEvent} 根本不会触发，
     * 所以只能像以前那样，在渲染前把她的背包类型临时换成精妙背包的某一档。
     * 人形模型完全不碰这里，避免和下面的自绘重复。
     */
    @SubscribeEvent
    public static void onRenderMaid(RenderMaidEvent event) {
        EntityMaid maid = asMaid(event.getMaid());
        if (maid == null || !maid.level().isClientSide) {
            return;
        }
        if (!MaidSophisticated.sophisticatedBackpacksLoaded() || !playerWantsBackpack(maid)) {
            return;
        }
        if (usesLivingEventPath(maid)) {
            // 人形模型：交给 RenderLivingEvent 自己画，这里什么都不用做
            return;
        }
        ItemStack top = topBackpack(maid);
        if (top.isEmpty() || !shouldShowSophisticated(maid) || !maid.getConfigManager().isShowBackpack()) {
            return;
        }
        IMaidBackpack current = maid.getMaidBackpackType();
        if (!SophMaidBackpack.isSophType(current)) {
            MaidBackpackLook.record(maid, current);
        }
        maid.setMaidBackpackType(SophMaidBackpack.forStack(top));
        SWAPPED.add(maid.getId());
        if (!loggedDecision) {
            loggedDecision = true;
            MaidSophisticated.LOGGER.info(
                    "Maid backpack appearance -> sophisticated (bedrock model, gecko/ysm maid): {} on maid {}",
                    top.getItem(), maid.getId());
        }
    }

    // ---------- 人形模型：遮掉她自己的背包，再把精妙背包画上去 ----------

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        // 这一帧已经用"换类型"方式显示了，别再画第二遍
        if (SWAPPED.contains(maid.getId())) {
            return;
        }
        if (!MaidSophisticated.sophisticatedBackpacksLoaded() || maid.isInvisible()
                || !playerWantsBackpack(maid)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        ItemStack stack = topBackpack(maid);
        if (stack.isEmpty()) {
            // 数据没到：顺手请服务端补发一次（节流），并保证不遮住她自己的背包
            requestState(maid);
            return;
        }
        if (!shouldShowSophisticated(maid)) {
            return;
        }
        // 遮住女仆模组自己那条背包层。这一步和"画精妙背包"放在同一个钩子里：
        // 只有确实要画的时候才遮，绝不会出现"遮了却没画"导致背上空空的情况。
        hideOwnBackpack(maid);
        if (!loggedDecision) {
            loggedDecision = true;
            MaidSophisticated.LOGGER.info(
                    "Maid backpack appearance -> sophisticated (item model): {} on maid {}",
                    stack.getItem(), maid.getId());
        }

        // 站姿高度 × 模型缩放 = 女仆在画面里大概有多高，背包按这个比例挂。
        float height = Math.max(0.6F,
                maid.getDimensions(Pose.STANDING).height * modelScale(event.getRenderer(), maid));
        float fit = Mth.clamp(height / BASE_HEIGHT, 0.62F, 1.25F);
        float yaw = Mth.rotLerp(event.getPartialTick(), maid.yBodyRotO, maid.yBodyRot);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // 先站到后背高度，再按身体朝向转过去（0 度时实体面朝 +Z，背面是 -Z）。
        pose.translate(0.0D, height * BACK_HEIGHT_RATIO, 0.0D);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.translate(0.0D, 0.0D, -BACK_DEPTH * fit);
        pose.scale(BACK_SCALE * fit, BACK_SCALE * fit, BACK_SCALE * fit);

        mc.getItemRenderer().renderStatic(stack, ClientEventHandler.WORN, event.getPackedLight(),
                OverlayTexture.NO_OVERLAY, pose, event.getMultiBufferSource(), mc.level, maid.getId());
        pose.popPose();
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (event.getEntity() instanceof EntityMaid maid) {
            unhide(maid);
        }
    }

    /** 一帧结束（世界 + 界面都画完了）：把所有临时改过的东西全部还原。 */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        restoreAll();
    }

    /** 退出世界 / 切维度：临时开关、主动请求节流都按实体 id 存着，必须清掉，免得串到下一个世界。 */
    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            reset();
        }
    }

    // ---------- 状态判定 ----------

    /**
     * 玩家自己有没有在女仆设置里关掉"显示背包"。
     *
     * 注意不能直接读 {@code isShowBackpack()}：为了遮住女仆自带的背包，
     * 我们每一帧都会把它临时设成 false，直接读就会误判成"玩家关了背包"，
     * 于是精妙背包也跟着不画了。所以临时遮掉期间要看记下来的原值。
     */
    private static boolean playerWantsBackpack(EntityMaid maid) {
        Boolean original = HIDDEN.get(maid.getId());
        return original != null ? original : maid.getConfigManager().isShowBackpack();
    }

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

    /**
     * 这一帧能不能走 {@link RenderLivingEvent} 自己画。
     * 普通人形（bedrock）模型走的是原版 LivingEntityRenderer，事件一定会触发；
     * 骨骼（gecko）模型和 YSM 模型会绕过它，那种情况只能用"换背包类型"的老办法。
     */
    private static boolean usesLivingEventPath(EntityMaid maid) {
        try {
            if (maid.isYsmModel()) {
                return false;
            }
            var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(maid);
            if (renderer instanceof EntityMaidRenderer maidRenderer) {
                MaidModelInfo info = maidRenderer.getMainInfo();
                return info == null || !info.isGeckoModel();
            }
        } catch (Throwable ignored) {
            // 模型信息还没准备好：按人形模型处理，最坏也只是这一帧位置差一点
            return true;
        }
        // 认不出的渲染器：不敢遮她的背包（遮了也未必画得出来），交给换类型那条路
        return false;
    }

    /** 女仆自定义模型自带的渲染缩放（有些模型整体放大/缩小过），拿不到就按 1 算。 */
    private static float modelScale(LivingEntityRenderer<?, ?> renderer, EntityMaid maid) {
        try {
            if (renderer instanceof EntityMaidRenderer maidRenderer) {
                MaidModelInfo info = maidRenderer.getMainInfo();
                if (info != null) {
                    return info.getRenderEntityScale();
                }
            }
        } catch (Throwable ignored) {
            // 模型信息还没准备好时按原尺寸画，绝不能因为外观把女仆渲染搞崩
        }
        return 1.0F;
    }

    // ---------- 遮住 / 还原女仆自己的背包 ----------

    /**
     * 把女仆模组自己那条背包层临时关掉：两条渲染层（普通人形、骨骼）都会先看这个开关。
     * 只改客户端内存里的同步字段，服务端和存档里她的背包一动不动。
     */
    private static void hideOwnBackpack(EntityMaid maid) {
        int id = maid.getId();
        if (!HIDDEN.containsKey(id)) {
            HIDDEN.put(id, maid.getConfigManager().isShowBackpack());
        }
        if (maid.getConfigManager().isShowBackpack()) {
            maid.getConfigManager().setShowBackpack(false);
        }
    }

    private static void unhide(EntityMaid maid) {
        Boolean previous = HIDDEN.remove(maid.getId());
        if (previous != null) {
            maid.getConfigManager().setShowBackpack(previous);
        }
    }

    /** 帧末兜底：万一某一帧因为异常没走到 Post，这里把开关和背包类型都补回去。 */
    private static void restoreAll() {
        if (!HIDDEN.isEmpty()) {
            for (Integer id : HIDDEN.keySet().toArray(new Integer[0])) {
                Boolean previous = HIDDEN.remove(id);
                EntityMaid maid = findMaid(id);
                if (maid != null && previous != null) {
                    maid.getConfigManager().setShowBackpack(previous);
                }
            }
        }
        if (!SWAPPED.isEmpty()) {
            for (Integer id : SWAPPED.toArray(new Integer[0])) {
                EntityMaid maid = findMaid(id);
                if (maid != null) {
                    MaidBackpackLook.restore(maid);
                }
            }
            SWAPPED.clear();
        }
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
        HIDDEN.clear();
        SWAPPED.clear();
        LAST_REQUEST.clear();
        MaidBackpackLook.restoreAll();
    }

    /** 女仆离开世界时清掉她的记录（{@code Events} 那边调用）。 */
    public static void forget(int entityId) {
        HIDDEN.remove(entityId);
        SWAPPED.remove(entityId);
        LAST_REQUEST.remove(entityId);
    }
}
