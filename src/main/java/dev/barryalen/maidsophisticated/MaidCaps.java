package dev.barryalen.maidsophisticated;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 女仆专属精妙背包槽 capability（NeoForge 1.21.1 新式实体能力）。
 *
 * 旧版 Forge 用 AttachCapabilitiesEvent 给每只女仆挂一个只建一次的实例；
 * 新式能力改为注册表 + provider 回调，provider 每次查询都会执行，
 * 所以这里自己按实体做一层缓存，保证"一只女仆一份数据"（老代码到处
 * 依赖实例身份：改数据后拿同一对象读、事件里改开关等）。
 */
public final class MaidCaps {
    public static final EntityCapability<MaidBackpackData, Void> MAID_BACKPACK =
            EntityCapability.createVoid(
                    ResourceLocation.fromNamespaceAndPath(MaidSophisticated.MOD_ID, "maid_backpack"),
                    MaidBackpackData.class);

    /**
     * 每只女仆一份稳定的 MaidBackpackData，<b>客户端和服务端各一张表</b>。
     *
     * <p>为什么必须按 side 分表：1.21.1 的 {@code Entity#equals/hashCode} 比的是
     * 实体 id（{@code id == other.id} / {@code hashCode() = id}），而单机（集成服务器）
     * 里客户端和服务端是同一个 JVM、两份实体实例、同一个 id。用普通的 HashMap /
     * WeakHashMap 当键，客户端的女仆会 {@code equals} 到同 id 的服务端女仆，
     * 于是客户端收同步包时写的是<b>服务端那份数据</b>（Render thread 改服务端
     * ItemStackHandler），还会触发"主动关闭原生界面"的保护，表现为
     * "从标签页打开精妙原生界面，两秒后自己关了"。分表之后两侧各拿各的。</p>
     *
     * <p>为什么用 {@link IdentityHashMap}：同上，Entity 的 equals/hashCode 是 id 比较，
     * 只有身份比较才能保证"一个实体实例一份数据"。代价是强引用——必须保留现有的
     * {@link #forget} / {@link #clear(boolean)} 清理点（实体离场、切维度、关服）。</p>
     */
    private static final Map<LivingEntity, MaidBackpackData> SERVER = new IdentityHashMap<>();
    private static final Map<LivingEntity, MaidBackpackData> CLIENT = new IdentityHashMap<>();

    private MaidCaps() {}

    /** 这个实体属于哪张表：客户端世界（渲染线程那份副本）用 CLIENT，其余一律 SERVER。 */
    private static Map<LivingEntity, MaidBackpackData> mapFor(LivingEntity entity) {
        return entity.level() != null && entity.level().isClientSide ? CLIENT : SERVER;
    }

    static MaidBackpackData getOrCreate(LivingEntity entity) {
        Map<LivingEntity, MaidBackpackData> map = mapFor(entity);
        synchronized (map) {
            MaidBackpackData existing = map.get(entity);
            if (existing != null) {
                return existing;
            }
            // 先把实例放进表里再读档：读档过程可能递归查询同一只女仆的能力，
            // 那时必须拿到同一个对象，不能现场再建一份。
            MaidBackpackData data = new MaidBackpackData(entity);
            map.put(entity, data);
            data.readFromEntity();
            return data;
        }
    }

    /**
     * 实体卸载/死亡摘数据时顺手清掉缓存，防止把旧实例算到复用的实体头上。
     *
     * <p>两张表都删，<b>不按 {@code entity.level()} 挑表</b>：实体离场时 {@code level()}
     * 有可能已经是 {@code null}（被别的模组提前 {@code setLevel(null)}、或实体在构造中途
     * 就触发离场），此时 {@link #mapFor} 会一律判成服务端，客户端的强引用就漏在表里、
     * 一直挂到关服。这里改成"哪张表里有就删哪张"：{@link IdentityHashMap} 按对象身份查，
     * 同一个实例不可能同时出现在两侧，所以多删一次一定不误伤别的实体。
     */
    static void forget(LivingEntity entity) {
        synchronized (CLIENT) {
            CLIENT.remove(entity);
        }
        synchronized (SERVER) {
            SERVER.remove(entity);
        }
    }

    /**
     * 清空指定那一侧的缓存。切维度时只清"正在卸载的那一侧"，关服时只清服务端。
     *
     * <p>以前两边一起清：单机切维度时客户端的 {@code LevelEvent.Unload} 会把<b>服务端</b>
     * 的数据、以及 LAN 主机里别人正开着的界面的上下文一起清掉。数据本身都是即时写回
     * 实体持久数据的，清缓存不丢东西，下次查询会重建——但两侧混清会让正在用的东西
     * 凭空换实例，所以必须分侧。</p>
     */
    static void clear(boolean clientSide) {
        Map<LivingEntity, MaidBackpackData> map = clientSide ? CLIENT : SERVER;
        synchronized (map) {
            map.clear();
        }
    }

    /**
     * 清掉只属于某一个维度的缓存项（维度卸载时用）。
     *
     * <p>以前维度卸载一律 {@link #clear(boolean)} 整表清空：服务端同时加载多个维度时
     *（多人服、别的模组动态卸载维度），卸载 A 维度会把 B 维度里女仆的数据实例一起作废。
     * 数据本身都是即时写回实体持久数据的，作废不丢东西，但正在开着原生界面的玩家
     * 会碰到"包装换了"的自查，界面被精妙自己踢掉。所以这里只删
     * {@code entry.level() == level} 的那批。</p>
     *
     * <p>两张表一起扫：{@code Level} 对象只属于一侧，所以这样删仍然不会串侧。
     * 参数取 {@link net.minecraft.world.level.LevelAccessor} 而不是 {@code Level}：
     * {@code LevelEvent.Unload#getLevel()} 的声明类型就是 {@code LevelAccessor}，
     * 实际传进来的仍然是那个维度的 {@code Level} 对象本身，所以下面按引用比较成立。</p>
     */
    static void clearLevel(net.minecraft.world.level.LevelAccessor level) {
        synchronized (CLIENT) {
            CLIENT.entrySet().removeIf(entry -> entry.getKey().level() == level);
        }
        synchronized (SERVER) {
            SERVER.entrySet().removeIf(entry -> entry.getKey().level() == level);
        }
    }

    /** 模组总线上的能力注册（只在车万女仆在场时才会被调到）。 */
    public static void register(RegisterCapabilitiesEvent event) {
        // 按注册表名找女仆实体类型；找不到就不注册，但必须留下日志——
        // 静默跳过会让页签退化成"只有界面能看到背包"，排查的人对着空日志发懵
        Optional<EntityType<?>> maidType = BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath(MaidSophisticated.TLM_MOD_ID, "maid"));
        if (maidType.isEmpty()) {
            MaidSophisticated.LOGGER.warn(
                    "注册表里找不到 {}:maid，女仆专属背包能力没有注册："
                            + "页签界面仍可能显示背包，但女仆 AI、背部渲染都读不到背包数据。"
                            + "请确认车万女仆版本是否改了女仆实体的注册名。",
                    MaidSophisticated.TLM_MOD_ID);
            return;
        }
        @SuppressWarnings("unchecked")
        EntityType<? extends LivingEntity> livingType = (EntityType<? extends LivingEntity>) maidType.get();
        event.registerEntity(MAID_BACKPACK, livingType, (entity, ctx) -> getOrCreate(entity));
    }
}
