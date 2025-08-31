package me.amuazm;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class RandomSpectateClient implements ClientModInitializer {
    // Constants.
    public static final String MOD_ID = "random-spectate";
    private static final long CHANGE_PLAYER_INTERVAL_MS = 1000 * 60 * 3; // 3 Minutes

    // States.
    private boolean isModEnabled = false;
    private long lastActionTime = 0;
    private UUID lastSpectatedUuid = null;
    private int tickCounter = 20;

    @Override
    public void onInitializeClient() {
        // Loop
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (tickCounter < 20) {
                tickCounter += 1;
                return;
            }

            tickCounter = 0;

            if (!isModEnabled) {
                return;
            }

            if (client.world == null || client.player == null) {
                return;
            }

            if (!client.player.isSpectator()) {
                return;
            }

            boolean isNextIntervalReached = System.currentTimeMillis() - lastActionTime >= CHANGE_PLAYER_INTERVAL_MS;
            boolean isCurrentTargetValid = lastSpectatedUuid != null && client.world.getPlayerByUuid(lastSpectatedUuid) != null;

            // Don't continue if we are still in our interval and the target is valid
            if (!isNextIntervalReached && isCurrentTargetValid) {
                return;
            }

            ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();

            if (networkHandler == null) {
                client.player.sendMessage(Text.literal("No network handler."));
                return;
            }

            client.player.sendMessage(Text.literal("Network handler found."));

            if (isNextIntervalReached) {
                // Spectate a new player
                client.player.sendMessage(Text.literal("Spectating a new player."));
                spectateRandomPlayer(client, networkHandler);
                lastActionTime = System.currentTimeMillis();
            } else {
                // See if we need a new player or try to set out camera to the target again
                boolean isNotInServer = networkHandler.getPlayerList().stream()
                        .noneMatch(playerListEntry -> playerListEntry.getProfile().getId().equals(lastSpectatedUuid));

                if (isNotInServer) {
                    client.player.sendMessage(Text.literal("Target no longer found in player list."));
                    spectateRandomPlayer(client, networkHandler);
                } else {
                    client.player.sendMessage(Text.literal("Target disappeared. Attempting to spectate again..."));
                    spectatePlayer(client, networkHandler, lastSpectatedUuid);
                }
            }
        });

        // Command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, commandRegistryAccess) ->
                dispatcher.register(ClientCommandManager.literal("randomspectate").executes(context -> {
                    lastActionTime = 0;

                    // Toggle the state of the mod
                    isModEnabled = !isModEnabled;
                    context.getSource().sendFeedback(Text.literal(
                            "Random Spectate is now " + (isModEnabled ? "enabled." : "disabled.")));

                    if (!isModEnabled) {
                        // Reset camera and last spectated uuid
                        MinecraftClient client = MinecraftClient.getInstance();

                        if (client.player != null) {
                            client.setCameraEntity(client.player);
                        }

                        lastSpectatedUuid = null;
                    }

                    return 1; // Command was executed successfully
                })));
    }

    private void spectateRandomPlayer(MinecraftClient client, ClientPlayNetworkHandler networkHandler) {
        if (client.world == null || client.player == null) {
            return;
        }

        List<PlayerListEntry> playerListEntries = new ArrayList<>(networkHandler.getPlayerList().stream().toList());
        // Ignore self
        playerListEntries.removeIf(playerListEntry -> playerListEntry.getProfile().getId().equals(client.player.getUuid()));
        // No repeat spectates
        playerListEntries.removeIf(playerListEntry -> playerListEntry.getProfile().getId().equals(lastSpectatedUuid));

        client.player.sendMessage(Text.literal(playerListEntries.size() + " player list entries found."));

        if (!playerListEntries.isEmpty()) {
            // Get a random player from the list and spectate them
            PlayerListEntry playerListEntry = playerListEntries.get(new Random().nextInt(playerListEntries.size()));
            spectatePlayer(client, networkHandler, playerListEntry.getProfile().getId());
        }
    }

    private void spectatePlayer(MinecraftClient client, ClientPlayNetworkHandler networkHandler, UUID targetUuid) {
        // To prevent this targetPlayerEntity from being spectated twice in a row
        lastSpectatedUuid = targetUuid;

        if (client.world == null || client.player == null) {
            return;
        }

        // Teleport to them
        networkHandler.sendPacket(new SpectatorTeleportC2SPacket(targetUuid));

        // Try get their player and spectate them
        new Thread(() -> {
            try {
                Thread.sleep(250);
                PlayerEntity targetPlayerEntity = client.world.getPlayerByUuid(targetUuid);

                int attemptsLeft = 4 * 5;

                // Repeatedly try get the target's PlayerEntity
                while (targetPlayerEntity == null && attemptsLeft > 0) {
                    Thread.sleep(250);
                    targetPlayerEntity = client.world.getPlayerByUuid(targetUuid);
                    attemptsLeft -= 1;
                }

                if (targetPlayerEntity == null) {
                    client.player.sendMessage(Text.literal("Could not get PlayerEntity for UUID " + targetUuid));
                    return;
                }

                // Set our camera to them
                client.setCameraEntity(targetPlayerEntity);
                client.player.sendMessage(Text.literal("You are now spectating " + targetPlayerEntity.getDisplayName().getString()), false);
            } catch (InterruptedException e) {
                client.player.sendMessage(Text.literal("Could not execute thread sleep: " + e));
            }
        }).start();
    }
}
