package dev.mrainator.tbsecho;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class EchoData extends SavedData {
    public static final class State {
        public int act, beat, journals, sessions, trial, trialState, trialDark, ending, deaths;
        public long played, actStart, nextBeat, nextAmbient, nextCache, nextMorse, nextTrial, trialStart, lastSeenMs, lastChatTick;
        public float presence, trialYaw;
        public boolean hasGrave, graveBuilt, graveVisited, hasDeath, deathSigned;
        public int gx, gy, gz, dx, dy, dz;
        public String lastChat = "", deathDim = "", name = "", token = "";
        public int webSeen, webAck;
        // не сохраняется: слежение за неподвижностью и маяк Морзе
        public transient double lastX, lastZ;
        public transient int stillTicks;
        public transient boolean hasBeacon;
        public transient int bx, by, bz;
        /** Язык игрока для веб-архива: 0 — неизвестен, 1 — русский, 2 — английский. Не сохраняется. */
        public transient int lang;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("act", act); t.putInt("beat", beat); t.putInt("journals", journals); t.putInt("sessions", sessions);
            t.putInt("trial", trial); t.putInt("trialState", trialState); t.putInt("trialDark", trialDark);
            t.putInt("ending", ending); t.putInt("deaths", deaths);
            t.putLong("played", played); t.putLong("actStart", actStart); t.putLong("nextBeat", nextBeat);
            t.putLong("nextAmbient", nextAmbient); t.putLong("nextCache", nextCache); t.putLong("nextMorse", nextMorse);
            t.putLong("nextTrial", nextTrial); t.putLong("trialStart", trialStart); t.putLong("lastSeenMs", lastSeenMs);
            t.putLong("lastChatTick", lastChatTick);
            t.putFloat("presence", presence); t.putFloat("trialYaw", trialYaw);
            t.putBoolean("hasGrave", hasGrave); t.putBoolean("graveBuilt", graveBuilt); t.putBoolean("graveVisited", graveVisited);
            t.putBoolean("hasDeath", hasDeath); t.putBoolean("deathSigned", deathSigned);
            t.putInt("gx", gx); t.putInt("gy", gy); t.putInt("gz", gz);
            t.putInt("dx", dx); t.putInt("dy", dy); t.putInt("dz", dz);
            t.putString("lastChat", lastChat); t.putString("deathDim", deathDim);
            t.putString("name", name); t.putString("token", token);
            t.putInt("webSeen", webSeen); t.putInt("webAck", webAck);
            return t;
        }

        static State load(CompoundTag t) {
            State s = new State();
            s.act = t.getInt("act"); s.beat = t.getInt("beat"); s.journals = t.getInt("journals"); s.sessions = t.getInt("sessions");
            s.trial = t.getInt("trial"); s.trialState = t.getInt("trialState"); s.trialDark = t.getInt("trialDark");
            s.ending = t.getInt("ending"); s.deaths = t.getInt("deaths");
            s.played = t.getLong("played"); s.actStart = t.getLong("actStart"); s.nextBeat = t.getLong("nextBeat");
            s.nextAmbient = t.getLong("nextAmbient"); s.nextCache = t.getLong("nextCache"); s.nextMorse = t.getLong("nextMorse");
            s.nextTrial = t.getLong("nextTrial"); s.trialStart = t.getLong("trialStart"); s.lastSeenMs = t.getLong("lastSeenMs");
            s.lastChatTick = t.getLong("lastChatTick");
            s.presence = t.getFloat("presence"); s.trialYaw = t.getFloat("trialYaw");
            s.hasGrave = t.getBoolean("hasGrave"); s.graveBuilt = t.getBoolean("graveBuilt"); s.graveVisited = t.getBoolean("graveVisited");
            s.hasDeath = t.getBoolean("hasDeath"); s.deathSigned = t.getBoolean("deathSigned");
            s.gx = t.getInt("gx"); s.gy = t.getInt("gy"); s.gz = t.getInt("gz");
            s.dx = t.getInt("dx"); s.dy = t.getInt("dy"); s.dz = t.getInt("dz");
            s.lastChat = t.getString("lastChat"); s.deathDim = t.getString("deathDim");
            s.name = t.getString("name"); s.token = t.getString("token");
            s.webSeen = t.getInt("webSeen"); s.webAck = t.getInt("webAck");
            return s;
        }
    }

    private final Map<UUID, State> players = new HashMap<>();

    public State get(UUID id) {
        return players.computeIfAbsent(id, k -> new State());
    }

    public java.util.Collection<State> all() {
        return players.values();
    }

    public void reset(UUID id) {
        players.remove(id);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        players.forEach((id, s) -> all.put(id.toString(), s.save()));
        tag.put("players", all);
        return tag;
    }

    private static EchoData load(CompoundTag tag, HolderLookup.Provider provider) {
        EchoData d = new EchoData();
        CompoundTag all = tag.getCompound("players");
        for (String k : all.getAllKeys()) {
            try {
                d.players.put(UUID.fromString(k), State.load(all.getCompound(k)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return d;
    }

    public static EchoData of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(EchoData::new, EchoData::load), "tbsecho");
    }
}
