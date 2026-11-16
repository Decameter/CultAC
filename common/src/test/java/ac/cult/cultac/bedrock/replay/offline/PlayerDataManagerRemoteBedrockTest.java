package ac.cult.cultac.bedrock.replay.offline;

import static ac.cult.cultac.utils.anticheat.PlayerDataManager.exemptsBedrockWithoutLocalGeyser;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.utils.anticheat.PlayerDataManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/** {@code exempt-bedrock-without-local-geyser}: skips Bedrock players only on a server without a Geyser bridge. */
public final class PlayerDataManagerRemoteBedrockTest {
    // Floodgate's UUID layout, which marks a Bedrock player even where the Floodgate API is absent.
    private static final UUID FLOODGATE_UUID = UUID.fromString("00000000-0000-0000-0009-01f2a3b4c5d6");

    private static final BooleanSupplier YES = () -> true;
    private static final BooleanSupplier NO = () -> false;
    private static final BooleanSupplier UNREAD = () -> {
        fail("evaluated a condition the earlier ones already decided");
        return false;
    };

    @Test
    public void exemptsOnlyBedrockPlayersWhileOnAndNoGeyserRunsHere() {
        assertTrue(exemptsBedrockWithoutLocalGeyser(true, NO, YES));

        assertFalse("a Java player is still checked", exemptsBedrockWithoutLocalGeyser(true, NO, NO));
        assertFalse("a local Geyser checks Bedrock players here", exemptsBedrockWithoutLocalGeyser(true, YES, UNREAD));
        assertFalse("off by default", exemptsBedrockWithoutLocalGeyser(false, UNREAD, UNREAD));
    }

    @Test
    public void defaultConfigStillChecksBedrockPlayersWithoutABridge() {
        OfflineCultTestBootstrap.installConfig();
        PlayerDataManager manager = new PlayerDataManager();
        User bedrock = ac.cult.cultac.network.TestUsers.create(
                new User.Profile(FLOODGATE_UUID, ".bedrock"), new EmbeddedChannel());
        try {
            assertTrue(manager.shouldCheck(bedrock));
            assertFalse(manager.isExemptUser(bedrock));
        } finally {
            manager.clearExemptions(bedrock);
            ((EmbeddedChannel) bedrock.getChannel()).finishAndReleaseAll();
        }
    }
}
