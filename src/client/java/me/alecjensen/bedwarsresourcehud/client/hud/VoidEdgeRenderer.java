package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws a bright red line along every block edge VoidEdgeTracker found that leads straight into a
 * long drop, so stepping off a ledge into the void is something you see coming instead of finding
 * out on the way down.
 *
 * Real world-space geometry via the vanilla Gizmos API (Gizmos#line, submitted through
 * LevelRenderEvents#BEFORE_GIZMOS), not a screen-space projection - that's what makes it get
 * properly depth-tested against the actual world (hidden behind a wall between you and the edge,
 * correctly foreshortened at a glancing angle) instead of behaving like a HUD sticker glued to the
 * screen; nothing here renders through walls or terrain. Called fresh every frame from the cached
 * edge list; nothing here does the actual block scanning, which is throttled and comparatively
 * expensive - see VoidEdgeTracker.
 */
public final class VoidEdgeRenderer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/VoidEdgeRenderer");
    private static final int VOID_NEAR_COLOR = 0xFFFF0000;
    private static final int VOID_FAR_COLOR = 0xFFFF8800;
    private static final int FALL_DAMAGE_COLOR = 0xFFFFFF00;
    private static final float LINE_WIDTH = 3.0f;

    private static boolean loggedFailure;
    private static boolean loggedSuccess;

    private VoidEdgeRenderer()
    {
    }

    public static void register()
    {
        LevelRenderEvents.BEFORE_GIZMOS.register(context ->
        {
            Minecraft client = Minecraft.getInstance();
            HudConfig config = HudConfig.get();
            if (client.player == null || client.level == null || (!config.showVoidEdgeWarning && !config.showFallDamageWarning))
            {
                return;
            }

            List<VoidEdgeTracker.Edge> edges = VoidEdgeTracker.getEdges();
            if (edges.isEmpty())
            {
                return;
            }

            // A void hazard (red/orange) always matters more than a merely-painful fall (yellow) -
            // if both happen to land on the same horizontal position, just don't draw the yellow
            // one there at all, rather than fighting over which renders "on top" of the other.
            Set<HorizontalKey> dangerPositions = new HashSet<>();
            for (VoidEdgeTracker.Edge edge : edges)
            {
                if (edge.type() != VoidEdgeTracker.EdgeType.FALL_DAMAGE)
                {
                    dangerPositions.add(HorizontalKey.of(edge));
                }
            }

            try
            {
                int submitted = 0;
                for (VoidEdgeTracker.Edge edge : edges)
                {
                    if (edge.type() == VoidEdgeTracker.EdgeType.FALL_DAMAGE && dangerPositions.contains(HorizontalKey.of(edge)))
                    {
                        continue;
                    }

                    Integer color = switch (edge.type())
                    {
                        case VOID_NEAR -> config.showVoidEdgeWarning ? VOID_NEAR_COLOR : null;
                        case VOID_FAR -> config.showVoidEdgeWarning ? VOID_FAR_COLOR : null;
                        case FALL_DAMAGE -> config.showFallDamageWarning ? FALL_DAMAGE_COLOR : null;
                    };
                    if (color == null)
                    {
                        continue;
                    }
                    Gizmos.line(edge.start(), edge.end(), color, LINE_WIDTH);
                    submitted++;
                }
                // Confirms BEFORE_GIZMOS is actually a valid place to call Gizmos.line() from -
                // logged once so a log pull after playing a match can prove it one way or the
                // other instead of silence being ambiguous between "working" and "never runs".
                if (!loggedSuccess && submitted > 0)
                {
                    LOGGER.info("Gizmos.line() succeeded from BEFORE_GIZMOS ({} edges submitted)", submitted);
                    loggedSuccess = true;
                }
            }
            // Gizmos.line() throws if called outside a bound GizmoCollector - this is the vanilla
            // block-outline hook point specifically so mods have somewhere valid to call it from,
            // but that's inferred from the API shape, not confirmed against real source, so this
            // is here to fail loud exactly once instead of silently drawing nothing every frame.
            catch (IllegalStateException e)
            {
                if (!loggedFailure)
                {
                    LOGGER.warn("Gizmos.line() failed from BEFORE_GIZMOS - void edge warning won't render", e);
                    loggedFailure = true;
                }
            }
        });
    }

    /** Identifies an edge by its horizontal footprint only (rounded to a fine grid) - two edges at the same X/Z line but slightly different sampled heights still count as "the same spot". */
    private record HorizontalKey(int x1, int z1, int x2, int z2)
    {
        static HorizontalKey of(VoidEdgeTracker.Edge edge)
        {
            return new HorizontalKey(
                    Math.round((float) edge.start().x * 100), Math.round((float) edge.start().z * 100),
                    Math.round((float) edge.end().x * 100), Math.round((float) edge.end().z * 100));
        }
    }
}
