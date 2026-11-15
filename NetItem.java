package com.tentnets.net;

import com.tentnets.ModRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Предмет-сеть.
 *
 *  ПКМ по блоку            — добавить опорную точку (до 8) в место клика;
 *  Shift + ПКМ             — натянуть сеть между точками (нужно минимум 3);
 *  ПКМ в воздух            — отменить последнюю точку;
 *  Shift + ПКМ в воздух    — если точек нет: снять сеть, на которую смотришь.
 */
public class NetItem extends Item {
    private static final String KEY = "tentnets_points";

    public static final int MAX_POINTS = 8;
    public static final double MAX_SPAN = 48.0;
    private static final double PICK_RANGE = 12.0;

    private final NetEntity.Kind kind;

    public NetItem(NetEntity.Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    // ---------------------------------------------------------------- хранение точек в предмете

    public static List<Vec3> readPoints(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = tag.getList(KEY, Tag.TAG_DOUBLE);
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i + 2 < list.size(); i += 3) {
            out.add(new Vec3(list.getDouble(i), list.getDouble(i + 1), list.getDouble(i + 2)));
        }
        return out;
    }

    private static void writePoints(ItemStack stack, List<Vec3> points) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (points.isEmpty()) {
            tag.remove(KEY);
        } else {
            ListTag list = new ListTag();
            for (Vec3 p : points) {
                list.add(DoubleTag.valueOf(p.x));
                list.add(DoubleTag.valueOf(p.y));
                list.add(DoubleTag.valueOf(p.z));
            }
            tag.put(KEY, list);
        }
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    // ---------------------------------------------------------------- взаимодействие

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);

        ItemStack stack = ctx.getItemInHand();

        if (player.isShiftKeyDown()) {
            confirm(level, player, stack);
            return InteractionResult.sidedSuccess(false);
        }

        List<Vec3> points = readPoints(stack);
        if (points.size() >= MAX_POINTS) {
            msg(player, "tentnets.max_points", MAX_POINTS);
            return InteractionResult.sidedSuccess(false);
        }

        // Точка — в место клика, чуть отодвинутая от грани (чтобы сеть не врезалась в блок).
        var face = ctx.getClickedFace();
        Vec3 raw = ctx.getClickLocation().add(face.getStepX() * 0.04, face.getStepY() * 0.04, face.getStepZ() * 0.04);
        Vec3 p = new Vec3(snap(raw.x), snap(raw.y), snap(raw.z));

        if (!points.isEmpty() && p.distanceTo(points.get(0)) > MAX_SPAN) {
            msg(player, "tentnets.too_far", (int) MAX_SPAN);
            return InteractionResult.sidedSuccess(false);
        }
        for (Vec3 q : points) {
            if (q.distanceTo(p) < 0.3) {
                msg(player, "tentnets.duplicate");
                return InteractionResult.sidedSuccess(false);
            }
        }

        points.add(p);
        writePoints(stack, points);
        level.playSound(null, p.x, p.y, p.z, SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.8F, 1.4F);
        msg(player, "tentnets.point_added", points.size(), MAX_POINTS);
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.sidedSuccess(stack, true);

        List<Vec3> points = readPoints(stack);

        if (player.isShiftKeyDown()) {
            if (points.size() >= 3) {
                confirm(level, player, stack);
            } else if (points.isEmpty()) {
                removeLookedAtNet(level, player);
            } else {
                msg(player, "tentnets.need_points");
            }
        } else if (!points.isEmpty()) {
            points.remove(points.size() - 1);
            writePoints(stack, points);
            msg(player, "tentnets.point_removed", points.size(), MAX_POINTS);
        } else {
            msg(player, "tentnets.help");
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private void confirm(Level level, Player player, ItemStack stack) {
        List<Vec3> points = readPoints(stack);
        if (points.size() < 3) {
            msg(player, "tentnets.need_points");
            return;
        }
        NetShape shape = NetShape.fromPoints(points);
        if (shape == null) {
            msg(player, "tentnets.invalid");
            return;
        }

        NetEntity net = ModRegistry.NET.get().create(level);
        if (net == null) return;
        net.setNet(kind, shape);
        if (!level.addFreshEntity(net)) return;

        writePoints(stack, List.of());
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        level.playSound(null, shape.center.x, shape.center.y, shape.center.z,
                SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.5F, 0.7F);
        msg(player, "tentnets.placed");
    }

    private void removeLookedAtNet(Level level, Player player) {
        if (!(level instanceof ServerLevel server)) return;

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(PICK_RANGE));

        NetEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : server.getAllEntities()) {
            if (e instanceof NetEntity net && !net.isRemoved()) {
                Vec3 hit = net.rayHit(eye, end);
                if (hit != null) {
                    double d = hit.distanceToSqr(eye);
                    if (d < best) {
                        best = d;
                        nearest = net;
                    }
                }
            }
        }
        if (nearest == null) {
            msg(player, "tentnets.help");
            return;
        }

        if (!player.getAbilities().instabuild) {
            ItemStack back = new ItemStack(nearest.getKind() == NetEntity.Kind.ANTI
                    ? ModRegistry.ANTI_DRONE_NET.get() : ModRegistry.MASK_NET.get());
            if (!player.getInventory().add(back)) {
                player.drop(back, false);
            }
        }
        nearest.discard();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.WOOL_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
        msg(player, "tentnets.removed");
    }

    // ---------------------------------------------------------------- подсветка выбранных точек

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
        super.inventoryTick(stack, level, holder, slot, selected);
        if (!selected || !(level instanceof ServerLevel server) || !(holder instanceof ServerPlayer sp)) return;
        if (server.getGameTime() % 8 != 0) return;

        List<Vec3> pts = readPoints(stack);
        if (pts.isEmpty()) return;

        for (Vec3 p : pts) {
            server.sendParticles(sp, ParticleTypes.END_ROD, true, p.x, p.y, p.z, 2, 0.02, 0.02, 0.02, 0.0);
        }
        // Контур будущей сети по порядку точек (по замкнутой ломаной).
        if (pts.size() >= 2) {
            for (int i = 0; i < pts.size(); i++) {
                Vec3 a = pts.get(i);
                Vec3 b = pts.get((i + 1) % pts.size());
                if (pts.size() == 2 && i == 1) break;
                int steps = Math.min(40, (int) (a.distanceTo(b) * 2));
                for (int s = 1; s < steps; s++) {
                    double t = s / (double) steps;
                    Vec3 q = a.add(b.subtract(a).scale(t));
                    server.sendParticles(sp, ParticleTypes.END_ROD, true, q.x, q.y, q.z, 1, 0, 0, 0, 0.0);
                }
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        String base = "tooltip." + com.tentnets.TentNetsMod.MODID + "." + kind.id;
        lines.add(Component.translatable(base + ".desc").withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.tentnets.controls.1").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("tooltip.tentnets.controls.2").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("tooltip.tentnets.controls.3").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        int n = readPoints(stack).size();
        if (n > 0) {
            lines.add(Component.translatable("tooltip.tentnets.points", n, MAX_POINTS)
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
        }
    }

    // ---------------------------------------------------------------- утилиты

    private static double snap(double v) {
        return Math.round(v * 16.0) / 16.0;
    }

    private static void msg(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
