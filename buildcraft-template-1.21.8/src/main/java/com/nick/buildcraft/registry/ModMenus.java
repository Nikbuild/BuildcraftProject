package com.nick.buildcraft.registry;

import com.nick.buildcraft.BuildCraft;
import com.nick.buildcraft.content.block.engine.StirlingEngineMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension; // ← use this helper
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    private ModMenus() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, BuildCraft.MODID);

    // Client ctor reads BlockPos from FriendlyByteBuf
    public static final DeferredHolder<MenuType<?>, MenuType<StirlingEngineMenu>> STIRLING_ENGINE =
            MENUS.register("stirling_engine",
                    () -> IMenuTypeExtension.create((id, inv, buf) -> new StirlingEngineMenu(id, inv, buf)));
}
