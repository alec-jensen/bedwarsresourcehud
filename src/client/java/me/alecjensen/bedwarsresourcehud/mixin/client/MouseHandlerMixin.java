package me.alecjensen.bedwarsresourcehud.mixin.client;

import me.alecjensen.bedwarsresourcehud.client.BedwarsresourcehudClient;
import me.alecjensen.bedwarsresourcehud.client.VersionChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URI;

/**
 * Vanilla toasts have no click handling at all - ToastManager only ever reads Toast#xPos/yPos to
 * position them for rendering; nothing checks mouse input against those bounds. This hooks the raw
 * GLFW mouse button callback (the earliest point any click is seen, independent of whatever screen
 * - or none, mid-match - happens to be open) to detect a left click landing on the update toast and
 * send the player to the release page, since that's the only way to make it clickable at all.
 *
 * Assumes the update toast is in slot 0 (i.e. the only toast currently showing) and fully
 * slid-in - both true in every realistic case, since nothing else in this mod triggers a toast and
 * a click can't land within the ~0.6s slide-in animation window anyway.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin
{
    @Inject(method = "onButton", at = @At("HEAD"))
    private void bedwarsresourcehud$onToastClick(long windowPointer, MouseButtonInfo mouseButtonInfo, int action, CallbackInfo ci)
    {
        if (action != 1 || mouseButtonInfo.button() != 0)
        {
            return;
        }

        VersionChecker.UpdateInfo update = VersionChecker.getAvailableUpdate();
        if (update == null)
        {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        ToastManager toastManager = client.gui.toastManager();
        SystemToast toast = toastManager.getToast(SystemToast.class, BedwarsresourcehudClient.UPDATE_TOAST_ID);
        if (toast == null)
        {
            return;
        }

        double mouseX = MouseHandler.getScaledXPos(client.getWindow(), client.mouseHandler.xpos());
        double mouseY = MouseHandler.getScaledYPos(client.getWindow(), client.mouseHandler.ypos());
        double toastLeft = client.getWindow().getGuiScaledWidth() - toast.width();

        if (mouseX < toastLeft || mouseY > toast.height())
        {
            return;
        }

        SystemToast.forceHide(toastManager, BedwarsresourcehudClient.UPDATE_TOAST_ID);
        ConfirmLinkScreen.confirmLinkNow(client.gui.screen(), URI.create(update.downloadUrl()));
        ci.cancel();
    }
}
