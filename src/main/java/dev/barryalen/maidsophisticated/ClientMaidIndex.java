package dev.barryalen.maidsophisticated;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 女仆 UUID → 网络实体 ID 的索引（客户端只能按 int 查实体，精妙背包界面在客户端解析上下文时要用）。 */
public final class ClientMaidIndex {
    private static final Map<UUID, Integer> BY_UUID = new ConcurrentHashMap<>();

    private ClientMaidIndex() {}

    static void track(Entity entity) {
        if (Events.isMaid(entity)) {
            BY_UUID.put(entity.getUUID(), entity.getId());
        }
    }

    static void untrack(Entity entity) {
        BY_UUID.remove(entity.getUUID());
    }

    /**
     * 客户端维度卸载 / 退出世界：整表清空。
     *
     * <p>不清的话，旧世界（或旧维度）那些没走到 {@code untrack} 的条目会一直留着。
     * {@link #resolve} 会再核对一次 UUID，所以不会解析到错人，纯粹是内存小尾巴；
     * 但客户端同一时刻只有一个 {@code ClientLevel}，清空后新世界 / 新维度的女仆
     * 会在 {@code EntityJoinLevelEvent} 里重新登记，所以清空本身不会让谁查不到。
     */
    static void clear() {
        BY_UUID.clear();
    }

    /** 两侧通用：服务端走 ServerLevel 的 UUID 查询，客户端走索引。 */
    public static LivingEntity resolve(Level level, UUID uuid) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getEntity(uuid) instanceof LivingEntity living ? living : null;
        }
        Integer id = BY_UUID.get(uuid);
        // 再核对一次 UUID：实体 id 会被回收，光按 id 查可能拿到同 id 的另一只女仆
        if (id != null && level.getEntity(id) instanceof LivingEntity living && living.getUUID().equals(uuid)) {
            return living;
        }
        return null;
    }
}
