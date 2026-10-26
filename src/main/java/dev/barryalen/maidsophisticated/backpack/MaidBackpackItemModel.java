package dev.barryalen.maidsophisticated.backpack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.barryalen.maidsophisticated.MaidSophisticated;
import dev.barryalen.maidsophisticated.client.MaidBackpackRenderer;
import dev.barryalen.maidsophisticated.client.SophBackpackRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 车万女仆背包层使用的“模型”：由 TLM 的原版背包层在它自己的挂点/姿势上调用，
 * 内部只画精妙背包物品模型。
 */
@OnlyIn(Dist.CLIENT)
public class MaidBackpackItemModel extends EntityModel<EntityMaid> {
    private static boolean loggedDrawn;

    @Override
    public void setupAnim(EntityMaid entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight,
                               int packedOverlay, float red, float green, float blue, float alpha) {
        EntityMaid maid = MaidBackpackRenderer.currentMaid();
        if (maid == null) {
            return;
        }
        ItemStack stack = MaidBackpackRenderer.backpackToShow(maid);
        if (stack.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        // 世界渲染看 TLM 层矩阵：Gecko 层做了 Z180，m11 变负。
        // 界面预览的相机还会额外旋转，直接看 m11 不可靠，需要固定翻正一次。
        // 但判断"是不是界面预览"不能简单用 mc.screen != null：开着背包/聊天框时，
        // 界面背后世界里的女仆也在画，那部分并没有 GUI 相机的翻正，强行翻会把
        // 她们的包画成上下颠倒。界面预览会把模型放大几十倍（原版从 30 倍起），
        // 世界里的缩放接近 1，用第一列的长度（不受旋转影响）区分这两种情况最稳。
        // 再加一道 mc.screen != null：界面预览一定是在某个界面里画的，
        // 这样世界里真被放大 5 倍以上的女仆（自定义模型可以带很大的整体缩放）
        // 就不会被误判成预览、平白翻个面。
        org.joml.Matrix4f pose = poseStack.last().pose();
        boolean yFlippedSpace = pose.m11() < 0.0F;
        float columnLengthSq = pose.m00() * pose.m00() + pose.m10() * pose.m10()
                + pose.m20() * pose.m20();
        boolean guiPreview = columnLengthSq > 25.0F && mc.screen != null;
        if (guiPreview) {
            yFlippedSpace = true;
        }
        // 批次跟着调用方走：界面预览（车万女仆主界面、我们的页签等）在
        // RenderLivingEvent.Pre 里接住界面自己的 bufferSource（画完由预览统一 flush），
        // 世界渲染用全局实体渲染那份（拿到的 VertexConsumer 本来就出自它）。
        SophBackpackRender.drawItem(maid, stack, poseStack,
                SophBackpackRender.buffersFor(mc), packedLight, packedOverlay, yFlippedSpace);

        if (!loggedDrawn) {
            loggedDrawn = true;
            MaidSophisticated.LOGGER.info(
                    "Soph backpack item drawn by TLM backpack layer: maid={}, item={}, yFlipped={}",
                    maid.getId(), stack.getItem(), yFlippedSpace);
        }
    }
}
