package dev.barryalen.maidsophisticated;

import com.github.tartaricacid.touhoulittlemaid.api.backpack.IMaidBackpack;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * "渲染期间临时换背包类型"的记账本。
 *
 * 女仆背上的背包只由车万女仆的渲染层按
 * {@code maid.getMaidBackpackType()} 决定，所以想看精妙背包就得在渲染那一刻把类型换掉，
 * 渲染完再换回来（只在客户端内存里换，服务端和她自己的存档完全不受影响）。
 *
 * 换掉之后，别的地方（比如界面上的"女仆背包"按钮）再问"她到底有没有自己的背包"就会问错，
 * 因此这里记着她原本的类型，供这些地方查询。
 */
public final class MaidBackpackLook {
    private static final Map<Integer, IMaidBackpack> ORIGINAL = new HashMap<>();
    private static final Map<Integer, WeakReference<EntityMaid>> MAIDS = new HashMap<>();

    private MaidBackpackLook() {}

    /** 记下她原本的背包类型（已经在用自己的背包类型时才算记录，重复调用不会覆盖）。 */
    public static void record(EntityMaid maid, IMaidBackpack original) {
        int id = maid.getId();
        if (ORIGINAL.containsKey(id)) {
            MAIDS.put(id, new WeakReference<>(maid));
            return;
        }
        ORIGINAL.put(id, original);
        MAIDS.put(id, new WeakReference<>(maid));
    }

    /** 这个女仆真正的背包类型：有记录就用记录，没有就用实体自己身上的。 */
    public static IMaidBackpack trueType(EntityMaid maid) {
        IMaidBackpack recorded = ORIGINAL.get(maid.getId());
        return recorded != null ? recorded : maid.getMaidBackpackType();
    }

    /** 把某个女仆的类型换回她自己的。 */
    public static void restore(EntityMaid maid) {
        IMaidBackpack original = ORIGINAL.remove(maid.getId());
        MAIDS.remove(maid.getId());
        if (original != null && maid.isAlive()) {
            maid.setMaidBackpackType(original);
        }
    }

    /** 一帧结束时统一换回来：兜底，保证任何逻辑/界面读到的都是真实类型。 */
    public static void restoreAll() {
        if (ORIGINAL.isEmpty()) {
            return;
        }
        for (Integer id : ORIGINAL.keySet().toArray(new Integer[0])) {
            WeakReference<EntityMaid> ref = MAIDS.get(id);
            EntityMaid maid = ref == null ? null : ref.get();
            IMaidBackpack original = ORIGINAL.get(id);
            if (maid != null && original != null && maid.isAlive()) {
                maid.setMaidBackpackType(original);
            }
            ORIGINAL.remove(id);
            MAIDS.remove(id);
        }
    }
}
