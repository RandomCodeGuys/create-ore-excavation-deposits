package uk.niknik.coedeposits.compat;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.platform.CoedepositsPlatform;

/**
 * Resolves a player's teammates for the {@code TEAM} reveal scope. Providers in
 * priority order:
 * <ol>
 *   <li><b>Open Parties and Claims</b> — party members (incl. offline UUIDs)</li>
 *   <li><b>FTB Teams</b> — team members (incl. offline UUIDs)</li>
 *   <li><b>Vanilla scoreboard team</b> — online members only (the scoreboard
 *       stores names, so offline members can't be resolved to UUIDs cheaply)</li>
 * </ol>
 * The first provider that yields members wins. Both mod integrations are
 * reflection-based against their public API interfaces (no compile dep, same
 * pattern as {@code XaeroBridge}) and fail soft: any API drift logs once at
 * DEBUG and the provider returns empty.
 *
 * <p><b>1.20.1 line:</b> in {@code platform-shared} (referenced by the shared
 * reveal logic in {@link uk.niknik.coedeposits.network.CoedepositsNetwork}); the
 * mod-presence check goes through {@link CoedepositsPlatform#isModLoaded} instead
 * of a loader-specific {@code ModList}.
 */
public final class TeamBridge {
    private TeamBridge() {}

    private static final String OPAC_MODID = "openpartiesandclaims";
    private static final String FTB_TEAMS_MODID = "ftbteams";

    /** One-shot "API drift" warning flags so a broken integration doesn't spam the log. */
    private static boolean opacWarned = false;
    private static boolean ftbWarned = false;

    /**
     * Teammates of {@code player} (their UUIDs, player themself excluded).
     * Empty set when the player is in no party/team or no provider is present.
     */
    public static Set<UUID> teammatesOf(ServerPlayer player) {
        Set<UUID> out = new HashSet<>();
        CoedepositsPlatform platform = CoedepositsPlatform.get();
        if (platform.isModLoaded(OPAC_MODID)) {
            collectOpac(player, out);
        }
        if (out.isEmpty() && platform.isModLoaded(FTB_TEAMS_MODID)) {
            collectFtb(player, out);
        }
        if (out.isEmpty()) {
            collectVanilla(player, out);
        }
        out.remove(player.getUUID());
        return out;
    }

    /**
     * Open Parties and Claims: {@code OpenPACServerAPI.get(server).getPartyManager()
     * .getPartyByMember(uuid)} → {@code IPartyAPI.getMemberInfoStream()} →
     * {@code IPartyPlayerInfoAPI.getUUID()}. Methods are resolved on the public
     * API classes/interfaces so reflection works across module boundaries.
     */
    private static void collectOpac(ServerPlayer player, Set<UUID> out) {
        try {
            Class<?> apiCls = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Object api = apiCls.getMethod("get", MinecraftServer.class).invoke(null, player.getServer());
            Object pm = apiCls.getMethod("getPartyManager").invoke(api);
            Method byMember = findMethod(pm.getClass(), "getPartyByMember", UUID.class);
            Object party = byMember.invoke(pm, player.getUUID());
            if (party == null) return;  // not in a party
            Class<?> infoCls = Class.forName("xaero.pac.common.parties.party.api.IPartyPlayerInfoAPI");
            Method getUuid = infoCls.getMethod("getUUID");
            try (Stream<?> members = (Stream<?>) Class
                    .forName("xaero.pac.common.parties.party.api.IPartyAPI")
                    .getMethod("getMemberInfoStream").invoke(party)) {
                members.forEach(m -> {
                    try {
                        out.add((UUID) getUuid.invoke(m));
                    } catch (ReflectiveOperationException ignored) { }
                });
            }
        } catch (Throwable t) {
            if (!opacWarned) {
                opacWarned = true;
                Coedeposits.LOGGER.debug("[coedeposits] Open Parties and Claims API drift — "
                        + "TEAM scope falls back to other providers", t);
            }
        }
    }

    /**
     * FTB Teams: {@code FTBTeamsAPI.api().getManager().getTeamForPlayer(player)}
     * → {@code Optional<Team>} → {@code Team.getMembers()} (Collection of UUIDs).
     */
    private static void collectFtb(ServerPlayer player, Set<UUID> out) {
        try {
            Class<?> apiCls = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Object api = apiCls.getMethod("api").invoke(null);
            Class<?> apiIfaceCls = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API");
            Object manager = apiIfaceCls.getMethod("getManager").invoke(api);
            Class<?> managerCls = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager");
            Optional<?> team = (Optional<?>) managerCls
                    .getMethod("getTeamForPlayer", ServerPlayer.class).invoke(manager, player);
            if (team.isEmpty()) return;
            Class<?> teamCls = Class.forName("dev.ftb.mods.ftbteams.api.Team");
            Object members = teamCls.getMethod("getMembers").invoke(team.get());
            for (Object id : (Iterable<?>) members) {
                if (id instanceof UUID uuid) out.add(uuid);
            }
        } catch (Throwable t) {
            if (!ftbWarned) {
                ftbWarned = true;
                Coedeposits.LOGGER.debug("[coedeposits] FTB Teams API drift — "
                        + "TEAM scope falls back to other providers", t);
            }
        }
    }

    /** Vanilla scoreboard team — online members only (scoreboard stores names, not UUIDs). */
    private static void collectVanilla(ServerPlayer player, Set<UUID> out) {
        PlayerTeam team = player.getTeam() instanceof PlayerTeam pt ? pt : null;
        if (team == null) return;
        for (ServerPlayer other : player.getServer().getPlayerList().getPlayers()) {
            if (team.equals(other.getTeam())) {
                out.add(other.getUUID());
            }
        }
    }

    /**
     * Resolve a public method on a possibly non-public implementation class by
     * walking its interfaces — invoking through the public API interface avoids
     * {@code IllegalAccessException} across module boundaries.
     */
    private static Method findMethod(Class<?> cls, String name, Class<?>... sig) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                try {
                    return iface.getMethod(name, sig);
                } catch (NoSuchMethodException ignored) { }
            }
        }
        return cls.getMethod(name, sig);
    }
}
