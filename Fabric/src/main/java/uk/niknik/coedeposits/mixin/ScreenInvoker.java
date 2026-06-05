package uk.niknik.coedeposits.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code @Invoker} accessor for the protected {@link Screen#addRenderableWidget}.
 *
 * <p>{@link GuiMapMixin} can't reach this member: it targets Xaero's {@code GuiMap}
 * and the member is <i>inherited</i> from vanilla {@code Screen}, but the Mixin
 * annotation processor can't walk Xaero's {@code GuiMap → ScreenBase → Screen}
 * chain (Xaero is a compile-only mod whose intermediate {@code ScreenBase} the AP
 * fails to resolve to vanilla {@code Screen}). A {@code @Shadow} of the inherited
 * method on GuiMap therefore produced an EMPTY refmap, silently breaking the whole
 * mixin in a production (intermediary) runtime.
 *
 * <p>Targeting {@code Screen} directly — a vanilla class the AP fully resolves —
 * makes Loom emit a correct {@code named→intermediary} refmap entry for
 * {@code addRenderableWidget} ({@code method_37063}). At runtime Xaero's GuiMap
 * IS a Screen, so {@link GuiMapMixin} casts {@code (Object)this} to this interface
 * and calls through it.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T coedeposits$addRenderableWidget(T widget);
}
