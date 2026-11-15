package com.tentnets.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tentnets.TentNetsMod;
import com.tentnets.net.NetEntity;
import com.tentnets.net.NetShape;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Рисует сеть как плоский многоугольник (веер треугольников), двусторонний.
 * Антидроновая — полупрозрачная сетка, маскировочная — непрозрачный камуфляж.
 */
public class NetRenderer extends EntityRenderer<NetEntity> {
    private static final ResourceLocation ANTI_TEX =
            ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, "textures/entity/anti_drone_net.png");
    private static final ResourceLocation MASK_TEX =
            ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, "textures/entity/mask_net.png");

    /** Сколько повторов текстуры на блок. */
    private static final float ANTI_UV_PER_BLOCK = 1.0F;
    private static final float MASK_UV_PER_BLOCK = 0.5F;

    public NetRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(NetEntity entity) {
        return entity.getKind() == NetEntity.Kind.ANTI ? ANTI_TEX : MASK_TEX;
    }

    @Override
    public void render(NetEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        NetShape shape = entity.getShape();
        if (shape == null) return;

        boolean anti = entity.getKind() == NetEntity.Kind.ANTI;
        VertexConsumer vc = buffer.getBuffer(anti
                ? RenderType.entityTranslucent(ANTI_TEX)
                : RenderType.entityCutoutNoCull(MASK_TEX));
        float uvScale = anti ? ANTI_UV_PER_BLOCK : MASK_UV_PER_BLOCK;

        // Центр сети может оказаться внутри блока (тёмный свет) — подтягиваем минимум.
        int light = LightTexture.pack(
                Math.max(LightTexture.block(packedLight), 4),
                Math.max(LightTexture.sky(packedLight), 8));

        PoseStack.Pose pose = poseStack.last();
        Vec3 origin = entity.position();
        Vec3[] v = shape.verts;
        float nx = (float) shape.normal.x;
        float ny = (float) shape.normal.y;
        float nz = (float) shape.normal.z;

        // Веер треугольников от вершины 0. Режим QUADS: четвёртая вершина дублирует третью.
        for (int i = 1; i + 1 < v.length; i++) {
            put(vc, pose, v[0], origin, shape, uvScale, light, nx, ny, nz);
            put(vc, pose, v[i], origin, shape, uvScale, light, nx, ny, nz);
            put(vc, pose, v[i + 1], origin, shape, uvScale, light, nx, ny, nz);
            put(vc, pose, v[i + 1], origin, shape, uvScale, light, nx, ny, nz);
        }
    }

    private static void put(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vec3 origin, NetShape shape,
                            float uvScale, int light, float nx, float ny, float nz) {
        vc.addVertex(pose, (float) (p.x - origin.x), (float) (p.y - origin.y), (float) (p.z - origin.z))
                .setColor(255, 255, 255, 255)
                .setUv((float) shape.uCoord(p) * uvScale, (float) shape.vCoord(p) * uvScale)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
