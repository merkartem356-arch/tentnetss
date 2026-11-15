package com.tentnets.net;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Натянутая сеть. Одна сущность = один плоский многоугольник в произвольной плоскости.
 * Форма синхронизируется клиенту строкой (vanilla SynchedEntityData), поэтому
 * дополнительных пакетов не нужно.
 */
public class NetEntity extends Entity {
    public enum Kind {
        ANTI("anti_drone_net"),
        MASK("mask_net");

        public final String id;

        Kind(String id) {
            this.id = id;
        }

        public static Kind byOrdinal(int i) {
            Kind[] all = values();
            return i >= 0 && i < all.length ? all[i] : ANTI;
        }
    }

    /** Сколько попаданий выдерживает антидроновая сеть. */
    public static final int MAX_HITS = 12;
    /** В каком радиусе от сети ищем летящие объекты (блоков). Покрывает скорости до ~6 блоков/тик. */
    private static final double SCAN_RANGE = 6.0;

    private static final EntityDataAccessor<String> DATA_SHAPE =
            SynchedEntityData.defineId(NetEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_KIND =
            SynchedEntityData.defineId(NetEntity.class, EntityDataSerializers.INT);

    // ВАЖНО: поля без инициализаторов — makeBoundingBox() вызывается из конструктора Entity.
    private NetShape shape;
    private AABB bounds;
    private int hits = MAX_HITS;

    public NetEntity(EntityType<? extends NetEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    // ---------------------------------------------------------------- данные

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SHAPE, "");
        builder.define(DATA_KIND, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_SHAPE.equals(key)) {
            rebuildShape();
        }
    }

    private void rebuildShape() {
        this.shape = NetShape.decode(this.entityData.get(DATA_SHAPE));
        if (this.shape != null) {
            this.bounds = this.shape.bounds.inflate(0.06);
            this.setBoundingBox(this.bounds);
        }
    }

    @Override
    protected AABB makeBoundingBox() {
        return this.bounds != null ? this.bounds : super.makeBoundingBox();
    }

    public void setNet(Kind kind, NetShape newShape) {
        this.entityData.set(DATA_KIND, kind.ordinal());
        this.entityData.set(DATA_SHAPE, newShape.encode());
        this.setPos(newShape.center);
    }

    public Kind getKind() {
        return Kind.byOrdinal(this.entityData.get(DATA_KIND));
    }

    public NetShape getShape() {
        return this.shape;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_KIND, tag.getInt("Kind"));
        this.entityData.set(DATA_SHAPE, tag.getString("Shape"));
        this.hits = tag.contains("Hits") ? tag.getInt("Hits") : MAX_HITS;
        if (this.shape != null) {
            this.setPos(this.shape.center);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Kind", this.entityData.get(DATA_KIND));
        tag.putString("Shape", this.entityData.get(DATA_SHAPE));
        tag.putInt("Hits", this.hits);
    }

    // ---------------------------------------------------------------- перехват

    @Override
    public void tick() {
        super.tick();

        Level level = this.level();
        if (level.isClientSide) return;

        if (this.shape == null) {
            this.discard();
            return;
        }
        if (getKind() != Kind.ANTI) return;

        List<Entity> candidates = level.getEntities(
                this,
                this.getBoundingBox().inflate(SCAN_RANGE),
                e -> !(e instanceof NetEntity) && !e.isRemoved() && NetRules.classify(e) != NetRules.Action.PASS
        );

        for (Entity e : candidates) {
            if (e.isRemoved()) continue;

            NetRules.Action action = NetRules.classify(e);
            // Отрезок движения за последний тик (от xo/yo/zo до текущей позиции), от центра хитбокса.
            Vec3 delta = e.position().subtract(e.xo, e.yo, e.zo);
            Vec3 cur = e.getBoundingBox().getCenter();
            Vec3 prev = cur.subtract(delta);
            double radius = Math.max(0.1, Math.min(e.getBbWidth(), e.getBbHeight()) * 0.5);

            Vec3 hit = this.shape.segmentHit(prev, cur, radius);
            if (hit == null) continue;

            onCatch(e, action, hit);
            if (this.isRemoved()) break;
        }
    }

    private void onCatch(Entity victim, NetRules.Action action, Vec3 p) {
        if (!(this.level() instanceof ServerLevel server)) return;

        if (action == NetRules.Action.EXPLODE) {
            // NONE — взрыв не ломает блоки, но бьёт существ рядом с точкой попадания.
            server.explode(victim, p.x, p.y, p.z, NetRules.power(victim), Level.ExplosionInteraction.NONE);
        } else {
            server.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 6, 0.15, 0.15, 0.15, 0.01);
            server.playSound(null, p.x, p.y, p.z, SoundEvents.WOOL_HIT, SoundSource.BLOCKS, 1.0F, 1.2F);
        }
        victim.discard();

        this.hits--;
        if (this.hits <= 0) {
            server.sendParticles(ParticleTypes.POOF, this.shape.center.x, this.shape.center.y, this.shape.center.z,
                    20, 0.5, 0.5, 0.5, 0.02);
            server.playSound(null, p.x, p.y, p.z, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.2F, 0.8F);
            this.discard();
        }
    }

    // ---------------------------------------------------------------- прочее

    /** Точка пересечения луча с сетью (для снятия сети игроком), либо null. */
    public Vec3 rayHit(Vec3 from, Vec3 to) {
        return this.shape == null ? null : this.shape.segmentHit(from, to, 0.08);
    }
}
