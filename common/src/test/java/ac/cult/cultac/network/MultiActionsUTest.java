package ac.cult.cultac.network;

import static ac.cult.cultac.network.ItemUseTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsU;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#handleKeybinds sends uses only from a tick it began without using an item, but its consumeClick loop keeps
 * sending the tick's queued clicks after one of them started a use. Crossbow rapid fire sends a use every tick of a
 * held charge.
 */
class MultiActionsUTest {
    @Test
    void aUseInATickBegunWhileUsingFlagsAndIsCancelled() {
        try (var fixture = fixture()) {
            var player = holdingCrossbow(fixture);
            startUsing(player);
            tickEnd(fixture, player);

            var event = use(fixture, player, 1);
            assertEquals(1, check(player).violations);
            assertTrue(event.isCancelled());
        }
    }

    @Test
    void everyUseOfAHeldChargeFlags() {
        try (var fixture = fixture()) {
            var player = holdingCrossbow(fixture);
            startUsing(player);
            for (int sequence = 1; sequence <= 5; sequence++) {
                tickEnd(fixture, player);
                assertTrue(use(fixture, player, sequence).isCancelled());
            }
            assertEquals(5, check(player).violations);
        }
    }

    @Test
    void queuedClicksAfterTheUseThatStartedInTheSameTickDoNotFlag() {
        try (var fixture = fixture()) {
            var player = holdingCrossbow(fixture);
            tickEnd(fixture, player);
            assertFalse(use(fixture, player, 1).isCancelled());
            // The first click's CrossbowItem#use started charging; the loop still sends the next queued click.
            startUsing(player);
            assertFalse(use(fixture, player, 2).isCancelled());
            assertEquals(0, check(player).violations);

            tickEnd(fixture, player);
            assertTrue(use(fixture, player, 3).isCancelled());
            assertEquals(1, check(player).violations);
        }
    }

    @Test
    void aUseAfterTheReleaseDoesNotFlag() {
        try (var fixture = fixture()) {
            var player = holdingCrossbow(fixture);
            startUsing(player);
            tickEnd(fixture, player);
            player.clientItemUse.release();
            tickEnd(fixture, player);
            assertFalse(use(fixture, player, 1).isCancelled());
            assertEquals(0, check(player).violations);
        }
    }

    @Test
    void aHeldChargeFlagsWithOtherItemsInTheHotbar() {
        try (var fixture = fixture()) {
            var player = holdingCrossbow(fixture);
            startUsing(player);
            // prepare() makes the movement model uncertain for any other hotbar item; the check must not inherit that.
            for (int tick = 0; tick < 3; tick++) {
                prepare(player, tick, crossbow(), mixedHotbar());
                tickEnd(fixture, player);
                assertTrue(use(fixture, player, tick + 1).isCancelled());
            }
            assertEquals(3, check(player).violations);
        }
    }

    @Test
    void aUseAfterTheHandStopsHoldingTheUsedItemDoesNotFlag() {
        try (var fixture = fixture()) {
            var player = holdingCrossbow(fixture);
            startUsing(player);
            // LivingEntity#updatingUsingItem stops the local use without a release; the obligation alone outlives it.
            player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET, arrows());
            prepare(player, 0, arrows(), mixedHotbar());
            tickEnd(fixture, player);
            assertFalse(use(fixture, player, 1).isCancelled());
            assertEquals(0, check(player).violations);
        }
    }

    @Test
    void aUseThatMayHaveCompletedDoesNotFlag() {
        try (var fixture = fixture()) {
            var player = holdingCrossbow(fixture);
            var held = player.getInventory().getHandItem(Hand.MAIN_HAND);
            player.clientItemUse.start(Hand.MAIN_HAND, held, 2, true);
            prepare(player, 0, crossbow(), mixedHotbar());
            tickEnd(fixture, player);
            assertFalse(use(fixture, player, 1).isCancelled());
            assertEquals(0, check(player).violations);
        }
    }

    private static void prepare(CultPlayer player, int tick, SimItemStack main, java.util.List<SimItemStack> hotbar) {
        player.clientItemUse.prepare(tick, 0, player.getClientVersion(), main, SimItemStack.EMPTY, hotbar);
    }

    private static java.util.List<SimItemStack> mixedHotbar() {
        var hotbar = new java.util.ArrayList<SimItemStack>(java.util.Collections.nCopies(9, SimItemStack.EMPTY));
        hotbar.set(0, crossbow());
        hotbar.set(1, arrows());
        return hotbar;
    }

    private static SimItemStack arrows() {
        return ItemUtil.modelItems().stack("minecraft:arrow", 64);
    }

    private static CultPlayer holdingCrossbow(RecordReceiveFixture fixture) {
        var player = fixture.player;
        player.gamemode = GameMode.SURVIVAL;
        player.entityID = 7;
        player.packetStateData.lastSlotSelected = 0;
        player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET, crossbow());
        check(player).setEnabled(true);
        return player;
    }

    private static void startUsing(CultPlayer player) {
        var held = player.getInventory().getHandItem(Hand.MAIN_HAND);
        player.clientItemUse.start(Hand.MAIN_HAND, held, ClientItemUseState.duration(held), true);
        assertTrue(player.isClientUsingItem());
    }

    private static PacketReceiveEvent<ServerboundUseItem> use(
            RecordReceiveFixture fixture, CultPlayer player, int sequence) {
        var packet = new ServerboundUseItem(Hand.MAIN_HAND, sequence, player.xRot, player.yRot);
        var event = new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.USE_ITEM, packet);
        check(player).onUseItem(event, player, packet);
        return event;
    }

    private static void tickEnd(RecordReceiveFixture fixture, CultPlayer player) {
        var packet = new Opaque(ServerboundPackets.CLIENT_TICK_END);
        check(player)
                .onClientTickEnd(
                        new PacketReceiveEvent<>(
                                fixture.user, ConnectionPhase.PLAY, ServerboundPackets.CLIENT_TICK_END, packet),
                        player,
                        packet);
    }

    private static MultiActionsU check(CultPlayer player) {
        return player.checkManager.getCheck(MultiActionsU.class);
    }

    private static SimItemStack crossbow() {
        return ItemUtil.modelItems().stack("minecraft:crossbow", 1);
    }
}
