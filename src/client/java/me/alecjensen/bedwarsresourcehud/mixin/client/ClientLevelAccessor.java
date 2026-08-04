package me.alecjensen.bedwarsresourcehud.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes ClientLevel's full entity set (protected getEntities()) instead of the render-culled
 * entitiesForRendering() that HudRenderer/GeneratorTracker used before - a generator pile behind
 * the player or just outside the render frustum is still loaded and still worth tracking.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor
{
    @Invoker("getEntities")
    LevelEntityGetter<Entity> bedwarsresourcehud$getEntities();
}
