package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.Hand;

/**
 * Minecraft#handleKeybinds only reaches Minecraft#startUseItem, the sole sender of use_item and use_item_on, in the
 * branch where LocalPlayer#isUsingItem is false: while using, it drains every queued use click and at most releases.
 * Its {@code while (keyUse.consumeClick()) startUseItem()} loop does not re-read isUsingItem, so a tick that starts
 * a use may send more uses for its remaining queued clicks. A use is therefore illegal only in a tick the client
 * began while already using, which the first use of the tick observes before this tick's own use could start one.
 *
 * <p>The server does not need the client to stop: ServerPlayerGameMode#useItem reaches CrossbowItem#use, which
 * fires a loaded crossbow without checking or ending the use, and CrossbowItem#onUseTick reloads it on the next tick
 * from the same use's charge. Every such use fires, which this check stops at the packet.
 */
@CheckData(
        name = "MultiActionsU",
        stableKey = "cult.multiactions.use_while_using",
        description = "Used an item while already using an item")
public class MultiActionsU extends Check implements CheckListener {
    // Whether the client was using when this tick's first use arrived; null until that use.
    private Boolean usingAtTickStart;

    public MultiActionsU(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        check(event);
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        check(event);
    }

    // handleKeybinds sends every use of a tick before LocalPlayer#tick sends its movement and the tick end.
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        usingAtTickStart = null;
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        usingAtTickStart = null;
    }

    private void check(PacketReceiveEvent<?> event) {
        if (!player.usesModernJavaItemUse()) return;
        // Not isClientUsingItem(): for movement it drops to "maybe using" whenever the hotbar holds another item.
        var inventory = player.getInventory();
        boolean using = player.clientItemUse.certainlyUsing(
                inventory.getHandItem(Hand.MAIN_HAND), inventory.getHandItem(Hand.OFF_HAND));
        if (usingAtTickStart == null) usingAtTickStart = using;
        // Both must hold: a use the model has since ended cannot be the one this packet is sent during.
        if (!usingAtTickStart || !using) return;
        if (flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
