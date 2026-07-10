package com.crabmods.instantworldmirror.client;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.testing.BlockEntityConstructionProbe;
import net.minecraft.client.Minecraft;

/**
 * Real-client regression gate for constructor-sensitive third-party block entities.
 */
public final class BlockEntityCompatibilityGate {
    private static final String PROPERTY = "instantworldmirror.blockEntityCompatibilityGate";
    private static final String ENVIRONMENT = "IWM_CLIENT_BLOCK_ENTITY_GATE";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY)
            || "true".equalsIgnoreCase(System.getenv(ENVIRONMENT));
    private static boolean running;

    private BlockEntityCompatibilityGate() {
    }

    public static void runIfEnabled() {
        if (!ENABLED || running) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || minecraft.options == null) {
            return;
        }

        running = true;
        int exitCode = 0;
        try {
            BlockEntityConstructionProbe.Result result = BlockEntityConstructionProbe.verify();
            InstantWorldMirror.LOGGER.info(
                    "IWM_CLIENT_BLOCK_ENTITY_GATE_METRICS constructed=1 preConstructionCalls={} postConstructionCalls={}",
                    result.preConstructionCalls(),
                    result.postConstructionCalls()
            );
            InstantWorldMirror.LOGGER.info("IWM_CLIENT_BLOCK_ENTITY_GATE_OK");
        } catch (Throwable throwable) {
            InstantWorldMirror.LOGGER.error("IWM_CLIENT_BLOCK_ENTITY_GATE_FAILED", throwable);
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
