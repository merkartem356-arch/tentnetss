package com.tentnets;

import com.tentnets.net.NetEntity;
import com.tentnets.net.NetItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRegistry {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TentNetsMod.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, TentNetsMod.MODID);

    public static final RegistryObject<EntityType<NetEntity>> NET = ENTITIES.register("net",
            () -> EntityType.Builder.<NetEntity>of(NetEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(16)
                    .updateInterval(Integer.MAX_VALUE)
                    .fireImmune()
                    .noSummon()
                    .build("net"));

    public static final RegistryObject<Item> ANTI_DRONE_NET = ITEMS.register("anti_drone_net",
            () -> new NetItem(NetEntity.Kind.ANTI, new Item.Properties().stacksTo(16)));

    public static final RegistryObject<Item> MASK_NET = ITEMS.register("mask_net",
            () -> new NetItem(NetEntity.Kind.MASK, new Item.Properties().stacksTo(16)));

    private ModRegistry() {
    }
}
