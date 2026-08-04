package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Draws a marker over every confirmed generator, no matter how far away or how many items are
 * currently sitting there: a small dot at all times (so there's something to aim at), and a full
 * two-line label - current item count, and time until the next spawn - once the player is actually
 * aiming close enough to it. This is pure screen-space math - project the generator's world
 * position through the camera's view/projection matrix to a pixel coordinate - rather than real 3D
 * world rendering, so it naturally ignores block occlusion (renders through walls) and never
 * shrinks with distance (same on-screen size throughout). "Whatever the client can see" is
 * whatever projects to a point on screen in front of the camera - there's no artificial distance
 * cutoff on top of that. Blocks don't occlude it (that's the whole point), but a player model
 * standing directly between the camera and the generator does - that's a deliberate exception, not
 * an oversight, checked separately via a real ray/bounding-box test against every other player.
 */
public final class GeneratorWaypointRenderer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/GeneratorWaypointRenderer");
    private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("bedwarsresourcehud", "generator_waypoints");
    private static final float AIM_TOLERANCE_DEGREES = 4.0f;
    private static final int LABEL_PADDING = 3;
    private static final int LABEL_Y_OFFSET = 20;
    private static final int LABEL_LINE_GAP = 1;
    private static final int LABEL_BACKGROUND_COLOR = 0x90000000;
    private static final int DOT_RADIUS = 2;
    private static final long DIAGNOSTIC_INTERVAL_MILLIS = 3000;

    private static long lastDiagnosticMillis;

    private GeneratorWaypointRenderer()
    {
    }

    public static void register()
    {
        HudElementRegistry.addLast(ELEMENT_ID, (graphics, tickCounter) ->
        {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null || !HudConfig.get().showGeneratorWaypoints)
            {
                return;
            }

            render(graphics);
        });
    }

    private static void render(GuiGraphicsExtractor graphics)
    {
        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.mainCamera();
        if (!camera.isInitialized())
        {
            return;
        }

        ClientLevel level = client.level;

        Vec3 cameraPos = camera.position();
        Vector3f forward = new Vector3f(camera.forwardVector());
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        Font font = client.font;

        List<GeneratorTracker.GeneratorWaypoint> confirmed = GeneratorTracker.getConfirmedWaypoints();
        boolean logDiagnostics = System.currentTimeMillis() - lastDiagnosticMillis >= DIAGNOSTIC_INTERVAL_MILLIS;
        if (logDiagnostics)
        {
            lastDiagnosticMillis = System.currentTimeMillis();
            LOGGER.info("confirmed waypoints: {}", confirmed.size());
        }

        for (GeneratorTracker.GeneratorWaypoint waypoint : confirmed)
        {
            double relX = waypoint.position().x - cameraPos.x;
            double relY = waypoint.position().y - cameraPos.y;
            double relZ = waypoint.position().z - cameraPos.z;
            double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);

            Vector3f direction = new Vector3f((float) (relX / distance), (float) (relY / distance), (float) (relZ / distance));
            float angleDegrees = (float) Math.toDegrees(Math.acos(Math.clamp(forward.dot(direction), -1f, 1f)));

            if (logDiagnostics)
            {
                LOGGER.info("waypoint type={} count={} distance={} angle={} withinAim={}",
                        waypoint.type(), waypoint.currentCount(), distance, angleDegrees,
                        angleDegrees <= AIM_TOLERANCE_DEGREES);
            }

            if (distance < 0.001)
            {
                continue;
            }

            // Full 4-component transform (not Matrix4f#transformProject) so the w component can be
            // checked before dividing - a point behind the camera has w <= 0 and would otherwise
            // silently project to a bogus on-screen spot instead of being rejected.
            Vector4f clip = new Vector4f((float) relX, (float) relY, (float) relZ, 1.0f);
            viewProjection.transform(clip);
            if (clip.w <= 0.0f)
            {
                continue;
            }

            int screenX = Math.round((clip.x / clip.w + 1f) / 2f * screenWidth);
            int screenY = Math.round((1f - clip.y / clip.w) / 2f * screenHeight);
            if (screenX < 0 || screenX > screenWidth || screenY < 0 || screenY > screenHeight)
            {
                continue;
            }

            if (isOccludedByPlayer(level, client.player, cameraPos, waypoint.position()))
            {
                continue;
            }

            // Always draw a small dot so there's something to aim at - the full label with the
            // item count only shows up once the player is actually looking close enough to it.
            int dotColor = dotColor(waypoint.type());
            graphics.fill(screenX - DOT_RADIUS, screenY - DOT_RADIUS, screenX + DOT_RADIUS, screenY + DOT_RADIUS, dotColor);

            if (angleDegrees > AIM_TOLERANCE_DEGREES)
            {
                continue;
            }

            String countLine;
            String timeLine;
            if (waypoint.staleSeconds() >= 0)
            {
                // We've lost live tracking of this one (out of range/sync) - count up how long
                // it's been instead of freezing a countdown, and mark the pile size as an estimate
                // rather than pretending we still know it exactly.
                countLine = waypoint.type().getDisplayName() + ": " + waypoint.currentCount() + " (~" + waypoint.estimatedCount() + ")";
                timeLine = "Next: +" + waypoint.staleSeconds() + "s";
            }
            else if (waypoint.countdownSeconds() < 0)
            {
                countLine = waypoint.type().getDisplayName() + ": " + waypoint.currentCount();
                timeLine = "Next: unknown";
            }
            else
            {
                countLine = waypoint.type().getDisplayName() + ": " + waypoint.currentCount();
                timeLine = "Next: " + waypoint.countdownSeconds() + "s";
            }
            int textWidth = Math.max(font.width(countLine), font.width(timeLine));
            int labelX = screenX - textWidth / 2;
            int labelY = screenY - LABEL_Y_OFFSET - font.lineHeight - LABEL_LINE_GAP;

            graphics.fill(labelX - LABEL_PADDING, labelY - LABEL_PADDING,
                    labelX + textWidth + LABEL_PADDING, labelY + font.lineHeight * 2 + LABEL_LINE_GAP + LABEL_PADDING, LABEL_BACKGROUND_COLOR);
            graphics.text(font, Component.literal(countLine), labelX, labelY, waypoint.type().getColor(), true);
            graphics.text(font, Component.literal(timeLine), labelX, labelY + font.lineHeight + LABEL_LINE_GAP, 0xFFAAAAAA, true);
        }
    }

    /**
     * A real line-of-sight test against every other player's hitbox - the segment from the camera
     * to the generator, clipped against each player's AABB. Unlike blocks (deliberately ignored),
     * a player physically standing in the way should hide the marker.
     */
    private static boolean isOccludedByPlayer(ClientLevel level, Player self, Vec3 cameraPos, Vec3 targetPos)
    {
        for (AbstractClientPlayer other : level.players())
        {
            if (other == self)
            {
                continue;
            }
            if (other.getBoundingBox().clip(cameraPos, targetPos).isPresent())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The always-visible dot uses its own palette rather than the item's usual HUD color - the
     * user specifically wants emerald green and a true blue for diamond (diamond's normal HUD
     * color is aqua, which doesn't read as clearly against sky/water backgrounds as a dot).
     */
    private static int dotColor(ResourceType type)
    {
        return switch (type)
        {
            case EMERALD -> 0xFF00FF00;
            case DIAMOND -> 0xFF3399FF;
            default -> type.getColor();
        };
    }
}
