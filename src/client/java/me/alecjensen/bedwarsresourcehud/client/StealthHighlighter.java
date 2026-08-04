package me.alecjensen.bedwarsresourcehud.client;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import me.alecjensen.bedwarsresourcehud.client.hud.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Decides whether a player should render with the glowing outline / a forced nametag while
 * sneaking or invisible. Both hooks are pure client-side render-time checks (a mixin into
 * Minecraft#shouldEntityAppearGlowing, and one into LivingEntityRenderer#shouldShowName) rather
 * than touching the entity's own synced data - Entity#setGlowingTag looks like the obvious way to
 * do this, but on the client Entity#isCurrentlyGlowing always reads the entity's networked shared
 * flags, which the server keeps re-sending (health/pose/effect changes etc.) and silently
 * overwrites right back to false, so it doesn't reliably stick.
 */
public final class StealthHighlighter
{
    private StealthHighlighter()
    {
    }

    /** Called from a mixin into Minecraft#shouldEntityAppearGlowing. */
    public static boolean shouldGlow(Entity entity)
    {
        return isHighlighted(entity);
    }

    /**
     * Called from a mixin into LivingEntityRenderer#shouldShowName, since that's the only place
     * that decides whether a nametag renders at all (and it normally hides invisible players'
     * names, which is exactly what needs bypassing here).
     */
    public static boolean shouldForceNameTag(LivingEntity entity)
    {
        return HudConfig.get().highlightShowNametag && isHighlighted(entity);
    }

    private static boolean isHighlighted(Entity entity)
    {
        if (!(entity instanceof AbstractClientPlayer player) || player == Minecraft.getInstance().player)
        {
            return false;
        }

        HudConfig config = HudConfig.get();
        boolean sneaking = config.highlightSneakingPlayers && player.isCrouching();
        boolean invisible = config.highlightInvisiblePlayers && player.isInvisible();
        boolean enemy = config.highlightEnemies && isEnemy(player);
        return sneaking || invisible || enemy;
    }

    private static boolean isEnemy(AbstractClientPlayer player)
    {
        Player self = Minecraft.getInstance().player;
        return self != null && !TeamUtil.isTeammate(self, player);
    }
}
