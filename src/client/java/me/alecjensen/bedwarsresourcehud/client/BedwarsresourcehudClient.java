package me.alecjensen.bedwarsresourcehud.client;

import me.alecjensen.bedwarsresourcehud.client.hud.HudRenderer;
import me.alecjensen.bedwarsresourcehud.client.hud.ResourceTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.Blocks;

public class BedwarsresourcehudClient implements ClientModInitializer
{
    private static final Component ENDER_CHEST_TITLE = Component.translatable("container.enderchest");

    @Override
    public void onInitializeClient()
    {
        HudRenderer.register();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ResourceTracker.reset());

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
        {
            if (level.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST)
            {
                ResourceTracker.onEnderChestPunched();
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(BedwarsresourcehudClient::onClientTick);
    }

    private static void onClientTick(Minecraft client)
    {
        if (client.player == null)
        {
            return;
        }

        ResourceTracker.tick(client.player);

        Screen screen = client.gui.screen();
        if (screen instanceof AbstractContainerScreen<?> containerScreen
                && containerScreen.getTitle().equals(ENDER_CHEST_TITLE)
                && containerScreen.getMenu() instanceof ChestMenu chestMenu)
        {
            ResourceTracker.updateFromOpenEnderChest(chestMenu.getContainer());
        }
    }
}
