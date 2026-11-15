package com.tentnets;

import com.tentnets.client.ClientSetup;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(TentNetsMod.MODID)
public class TentNetsMod {
    public static final String MODID = "tentnets";

    public TentNetsMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModRegistry.ITEMS.register(modBus);
        ModRegistry.ENTITIES.register(modBus);
        modBus.addListener(this::addToCreativeTab);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModRegistry.ANTI_DRONE_NET.get());
            event.accept(ModRegistry.MASK_NET.get());
        }
    }
}
