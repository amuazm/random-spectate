package me.amuazm;

import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket;
import net.minecraft.text.Text;

public class RandomSpectateClient implements ClientModInitializer {
  public static final String MOD_ID = "random-spectate";
  private static final long INTERVAL_MS = 1000 * 60 * 3; // 3 Minutes
  private boolean isModEnabled = false;
  private long lastActionTime = 0;
  private UUID lastSpectatedUuid = null;
  private int tickCounter = 20;

  @Override
  public void onInitializeClient() {
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (tickCounter < 20) {
            tickCounter += 1;
            return;
          }
          tickCounter = 0;

          if (client.world == null || client.player == null) {
            return;
          }

          boolean isSpectator = client.player.isSpectator();
          boolean nextIntervalReached = System.currentTimeMillis() - lastActionTime >= INTERVAL_MS;
          boolean targetPlayerGone =
              lastSpectatedUuid != null && client.world.getPlayerByUuid(lastSpectatedUuid) == null;
          ClientPlayNetworkHandler networkHandler;

          if (isModEnabled && isSpectator && (nextIntervalReached || targetPlayerGone)) {
            networkHandler = MinecraftClient.getInstance().getNetworkHandler();

            if (networkHandler == null) {
              client.player.sendMessage(Text.literal("No network handler."));
              return;
            }

            client.player.sendMessage(Text.literal("Network handler found."));
          } else {
            return;
          }

          if (nextIntervalReached) {
            client.player.sendMessage(Text.literal("Changing target."));
            spectateRandomPlayer(client, networkHandler);
            lastActionTime = System.currentTimeMillis();
          } else {
            boolean leftServer = !networkHandler.getPlayerUuids().contains(lastSpectatedUuid);
            if (leftServer) {
              client.player.sendMessage(
                  Text.literal("Target no longer in server. Changing target."));
              spectateRandomPlayer(client, networkHandler);
            } else {
              client.player.sendMessage(Text.literal("Target disappeared. Moving to target."));
              spectatePlayer(client, networkHandler, lastSpectatedUuid);
            }
          }
        });

    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, commandRegistryAccess) ->
            dispatcher.register(
                ClientCommandManager.literal("randomspectate")
                    .executes(
                        context -> {
                          lastActionTime = 0;
                          isModEnabled = !isModEnabled; // Toggle the state of the mod
                          context
                              .getSource()
                              .sendFeedback(
                                  Text.literal(
                                      "Random Spectate is now "
                                          + (isModEnabled ? "enabled." : "disabled.")));
                          return 1; // Command was executed successfully
                        })));
  }

  private void spectateRandomPlayer(
      MinecraftClient client, ClientPlayNetworkHandler networkHandler) {
    if (client.world == null || client.player == null) {
      return;
    }

    List<UUID> playerUuids = new ArrayList<>(networkHandler.getPlayerUuids());
    playerUuids.remove(client.player.getUuid());
    playerUuids.remove(lastSpectatedUuid);

    client.player.sendMessage(Text.literal(playerUuids.size() + " players found."));

    if (!playerUuids.isEmpty()) {
      UUID targetUuid = playerUuids.get(new Random().nextInt(playerUuids.size()));

      spectatePlayer(client, networkHandler, targetUuid);
    }
  }

  private void spectatePlayer(
      MinecraftClient client, ClientPlayNetworkHandler networkHandler, UUID targetUuid) {
    lastSpectatedUuid = targetUuid;
    if (client.world == null || client.player == null) {
      return;
    }
    networkHandler.sendPacket(new SpectatorTeleportC2SPacket(targetUuid));

    new Thread(
            () -> {
              try {
                Thread.sleep(250);
                PlayerEntity target = client.world.getPlayerByUuid(targetUuid);

                int attemptsLeft = 4 * 5;

                while (target == null && attemptsLeft > 0) {
                  Thread.sleep(250);
                  target = client.world.getPlayerByUuid(targetUuid);
                  attemptsLeft -= 1;
                }

                if (target == null) {
                  client.player.sendMessage(Text.literal("Could not get PlayerEntity."));
                  return;
                }

                client.setCameraEntity(target); // Spectate the selected player
                client.player.sendMessage(
                    Text.literal("You are now spectating " + target.getDisplayName().getString()),
                    false);
              } catch (InterruptedException e) {
                client.player.sendMessage(Text.literal("Could not execute thread sleep: " + e));
              }
            })
        .start();
  }
}
