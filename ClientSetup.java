package com.tentnets.client;

import com.tentnets.ModRegistry;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;

/** Только клиент. Загружается из TentNetsMod лишь при Dist.CLIENT. */
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientSetup::registerRenderers);
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::onNameTag);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModRegistry.NET.get(), NetRenderer::new);
    }
}
