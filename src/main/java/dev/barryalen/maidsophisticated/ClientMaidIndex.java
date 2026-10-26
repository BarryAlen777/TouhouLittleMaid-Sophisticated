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
