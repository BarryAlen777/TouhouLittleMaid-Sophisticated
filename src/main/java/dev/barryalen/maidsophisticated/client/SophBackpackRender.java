package dev.barryalen.maidsophisticated.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.p3pp3rf1y.sophisticatedbackpacks.client.ClientEventHandler;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 在车万女仆原版背包层给的 PoseStack 上，画精妙背包物品模型。
 *
 * <p>位置/坐躺/骑乘/缩放都由 TLM 的 {@code LayerMaidBackpack} /
 * {@code GeckoLayerMaidBackpack} 负责，这里只做“物品模型转成背在背后”的朝向和
 * 原版中背包大小。不是自己算世界坐标。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SophBackpackRender {
    /** 车万女仆原版“中背包”的观感大小。 */
    private static final float SCALE = 0.8F;

    /**
     * 这一次渲染要落进的批处理器。
     *
     * <p>女仆背上的精妙背包是挂在车万女仆的背包"层"里画的，层拿到的只有 VertexConsumer，
     * 反推不回调用方的 MultiBufferSource；但 Forge 在实体渲染前后抛的
     * {@code RenderLivingEvent} 带着的就是调用方那一份——世界里是全局实体批次，
     * 界面 3D 预览是 {@code GuiGraphics.bufferSource()}（原版预览方法画完统一 flush）。
     * 接住它，物品顶点才能和女仆身体同批提交。</p>
     *
     * <p>用栈而不是单个字段：渲染会嵌套（车万女仆的 Gecko/YSM 渲染器本身就是从
     * {@code RenderLivingEvent.Pre} 里再发起一次渲染，某些附加层还会在里面再画一个实体）。
     * 内层画完出栈就回到外层那一份，不会把外层提前清空。</p>
     */
    private static final Deque<MultiBufferSource> BUFFER_STACK = new ArrayDeque<>();

    /** 压入一层"这次渲染用这个批次"，返回 true 表示确实压了（调用方要配一次 pop）。 */
    public static boolean pushBuffers(MultiBufferSource buffers) {
        if (buffers == null) {
            return false;
        }
        BUFFER_STACK.push(buffers);
        return true;
    }

    /** 与 {@link #pushBuffers} 配对：弹掉一层，自动回落到外层（没有外层就用全局批次）。 */
    public static void popBuffers() {
        if (!BUFFER_STACK.isEmpty()) {
            BUFFER_STACK.pop();
        }
    }

    /** 帧末兜底：万一哪一层因为异常少弹了一次，这里整摞清干净。 */
    public static void clearBuffers() {
        BUFFER_STACK.clear();
    }

    /** 这一次背包绘制该进哪个批次：预览/嵌套期间用最内层那份，其余用全局实体渲染的。 */
    public static MultiBufferSource buffersFor(Minecraft mc) {
        MultiBufferSource top = BUFFER_STACK.peek();
        return top != null ? top : mc.renderBuffers().bufferSource();
    }

    /** TLM Gecko/YSM 翻转坐标里的中心。 */
    private static final float GECKO_CENTER_Y = 0.85F;
    private static final float GECKO_CENTER_Z = 0.05F;

    /** 普通人形层中心。 */
    private static final float HUMAN_CENTER_Y = 0.40F;
    private static final float HUMAN_CENTER_Z = 0.05F;

    private SophBackpackRender() {}

    public static void drawItem(EntityMaid maid, ItemStack stack, PoseStack poseStack,
                                MultiBufferSource buffer, int packedLight, int packedOverlay,
                                boolean yFlippedSpace) {
        if (maid == null || stack == null || stack.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        poseStack.pushPose();
        try {
            if (yFlippedSpace) {
                // TLM Gecko/YSM 层在挂点之后做了 translate(0,1,0.25)+Z180。
                // 这套坐标 Y 朝下，0.85 是往下压。
                poseStack.translate(0.0F, GECKO_CENTER_Y, GECKO_CENTER_Z);
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
            } else {
                poseStack.translate(0.0F, HUMAN_CENTER_Y, HUMAN_CENTER_Z);
            }
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            poseStack.scale(SCALE, SCALE, SCALE);

            int overlay = packedOverlay;
            if (overlay < 0) {
                overlay = LivingEntityRenderer.getOverlayCoords(maid, 0.0F);
            }
            mc.getItemRenderer().renderStatic(
                    stack,
                    ClientEventHandler.WORN,
                    packedLight,
                    overlay,
                    poseStack,
                    buffer,
                    mc.level,
                    maid.getId());
        } finally {
            poseStack.popPose();
        }
    }
}
