package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The enemy glow (StealthHighlighter) only renders when an enemy is actually within the camera's
 * view frustum - someone directly behind you gives zero indication, glow or not. This fills that
 * gap: for any nearby enemy currently NOT on screen, draw a small marker clamped to the edge of
 * the screen in their real direction, so there's some 360-degree awareness instead of only
 * whatever happens to be in front of you.
 *
 * The direction is computed from the camera's own right/up basis vectors (dot products against
 * the vector to the target), not from the projection matrix - projecting a point behind the
 * camera through the projection matrix and naively using the result has a sign-flip problem, since
 * the same screen-space quadrant can come from two different real-world directions depending on
 * whether the point is in front of or behind the camera. Dot products against right/up don't have
 * that ambiguity.
 *
 * Markers are colored by the target's own scoreboard team color instead of one fixed color, so the
 * radar edge doubles as "which team is that" at a glance. "Players" with no team at all are
 * excluded rather than shown red-by-default - real match combatants are always on a team once the
 * game starts, so a teamless one is almost certainly a shop/lobby NPC implemented as a fake player
 * entity rather than a villager or armor stand.
 */
public final class ThreatRadarRenderer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/ThreatRadarRenderer");
    private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("bedwarsresourcehud", "threat_radar");
    private static final int SCREEN_EDGE_MARGIN = 20;
    private static final int FALLBACK_MARKER_COLOR = 0xFFFF3333;
    private static final double ARC_HALF_WIDTH_RADIANS = Math.toRadians(2.25);
    private static final double ARC_DOT_RADIUS = 1.5;
    // Samples are spaced by arc *length*, not a fixed angle - a fixed angular step leaves visible
    // gaps between the stamped dots at a large screen-space radius (the arc-length per degree
    // grows with radius), which is what made the ring look like scattered dust instead of a solid
    // arc. Spacing every couple of pixels keeps consecutive dots overlapping at any radius.
    private static final double ARC_SAMPLE_SPACING_PX = 2.0;

    private static final Set<UUID> loggedNoTeam = new HashSet<>();

    private ThreatRadarRenderer()
    {
    }

    public static void register()
    {
        HudElementRegistry.addLast(ELEMENT_ID, (graphics, tickCounter) ->
        {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null || !HudConfig.get().showThreatRadar)
            {
                return;
            }

            render(client, graphics);
        });
    }

    private static void render(Minecraft client, GuiGraphicsExtractor graphics)
    {
        Camera camera = client.gameRenderer.mainCamera();
        if (!camera.isInitialized())
        {
            return;
        }

        HudConfig config = HudConfig.get();
        double maxRangeSq = config.threatRadarRange * config.threatRadarRange;

        Vec3 cameraPos = camera.position();
        Vector3f up = new Vector3f(camera.upVector());
        Vector3f right = new Vector3f(camera.leftVector()).negate();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;
        double edgeRadius = Math.min(screenWidth, screenHeight) / 2.0 - SCREEN_EDGE_MARGIN;

        ClientLevel level = client.level;
        Player self = client.player;

        for (AbstractClientPlayer other : level.players())
        {
            if (other == self || TeamUtil.isTeammate(self, other))
            {
                continue;
            }

            String profileName = other.getGameProfile().name();
            if (TeamUtil.isLikelyNpc(profileName))
            {
                if (loggedNoTeam.add(other.getUUID()))
                {
                    LOGGER.info("Excluding \"player\" {} ({}) from threat radar - likely an NPC", profileName, other.getUUID());
                }
                continue;
            }

            Vec3 targetPos = other.getEyePosition();
            double relX = targetPos.x - cameraPos.x;
            double relY = targetPos.y - cameraPos.y;
            double relZ = targetPos.z - cameraPos.z;
            double distanceSq = relX * relX + relY * relY + relZ * relZ;
            if (distanceSq > maxRangeSq || distanceSq < 0.001)
            {
                continue;
            }

            if (isOnScreen(viewProjection, relX, relY, relZ, screenWidth, screenHeight))
            {
                // Already visible via the normal glow highlight - the radar is only for the blind
                // spot outside the view frustum, not a second indicator for what you can already see.
                continue;
            }

            double distance = Math.sqrt(distanceSq);
            Vector3f direction = new Vector3f((float) (relX / distance), (float) (relY / distance), (float) (relZ / distance));
            double dx = direction.dot(right);
            double dy = -direction.dot(up);
            double angle = Math.atan2(dy, dx);

            int teamColor = other.getTeamColor();
            int markerColor = 0xFF000000 | (teamColor == 0 ? FALLBACK_MARKER_COLOR : teamColor);
            drawArcSlice(graphics, centerX, centerY, edgeRadius, angle, markerColor);
        }
    }

    /**
     * Draws a short highlighted slice of the radar ring's own outline, centered on the target's
     * angle, rather than a separate dot floating near it - the marker reads as part of the ring
     * itself lighting up in that direction. No native arc primitive to draw with, so this walks the
     * arc in small angular steps and stamps a small filled circle (rasterized as horizontal fill
     * spans, since there's no circle primitive either) at each sampled point.
     */
    private static void drawArcSlice(GuiGraphicsExtractor graphics, int centerX, int centerY, double radius, double angle, int color)
    {
        double arcLength = radius * (2 * ARC_HALF_WIDTH_RADIANS);
        int samples = Math.max(1, (int) Math.ceil(arcLength / ARC_SAMPLE_SPACING_PX));
        double stepRadians = (2 * ARC_HALF_WIDTH_RADIANS) / samples;

        for (int i = 0; i <= samples; i++)
        {
            double pointAngle = angle - ARC_HALF_WIDTH_RADIANS + i * stepRadians;
            int pointX = centerX + (int) Math.round(radius * Math.cos(pointAngle));
            int pointY = centerY + (int) Math.round(radius * Math.sin(pointAngle));
            drawFilledCircle(graphics, pointX, pointY, ARC_DOT_RADIUS, color);
        }
    }

    private static void drawFilledCircle(GuiGraphicsExtractor graphics, int centerX, int centerY, double radius, int color)
    {
        int intRadius = (int) Math.ceil(radius);
        for (int dy = -intRadius; dy <= intRadius; dy++)
        {
            double remaining = radius * radius - (double) dy * dy;
            if (remaining < 0)
            {
                continue;
            }
            int halfWidth = (int) Math.round(Math.sqrt(remaining));
            graphics.fill(centerX - halfWidth, centerY + dy, centerX + halfWidth + 1, centerY + dy + 1, color);
        }
    }

    private static boolean isOnScreen(Matrix4f viewProjection, double relX, double relY, double relZ, int screenWidth, int screenHeight)
    {
        Vector4f clip = new Vector4f((float) relX, (float) relY, (float) relZ, 1.0f);
        viewProjection.transform(clip);
        if (clip.w <= 0.0f)
        {
            return false;
        }

        int screenX = Math.round((clip.x / clip.w + 1f) / 2f * screenWidth);
        int screenY = Math.round((1f - clip.y / clip.w) / 2f * screenHeight);
        return screenX >= 0 && screenX <= screenWidth && screenY >= 0 && screenY <= screenHeight;
    }
}
