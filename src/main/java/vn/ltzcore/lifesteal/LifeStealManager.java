package vn.ltzcore.lifesteal;

import vn.ltzcore.core.Reflect;
import vn.ltzcore.storage.Database;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LifeStealManager {
    private final Object plugin;
    private final Database db;
    private final int startHearts;
    private final int maxHearts;
    private final int heartsPerKill;
    private final int heartsLost;
    private final int durationSeconds;
    private final Map<String,Long> antiFarm = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile long endAt;
    private volatile long eventStartedAt;

    public LifeStealManager(Object plugin, Database db, int startHearts, int maxHearts, int heartsPerKill, int heartsLost, int durationSeconds) throws Exception {
        this.plugin=plugin; this.db=db; this.startHearts=Math.max(1,startHearts); this.maxHearts=Math.max(this.startHearts,maxHearts);
        this.heartsPerKill=Math.max(1,heartsPerKill); this.heartsLost=Math.max(1,heartsLost); this.durationSeconds=durationSeconds;
        this.enabled=Boolean.parseBoolean(db.getSetting("lifesteal.enabled","false"));
        this.endAt=Long.parseLong(db.getSetting("lifesteal.endAt","0"));
        this.eventStartedAt=Long.parseLong(db.getSetting("lifesteal.eventStartedAt","0"));
        if(this.enabled && this.endAt>0 && System.currentTimeMillis()>=this.endAt) stop();
    }

    public boolean enabled() { return enabled; }
    public boolean tick() {
        if (enabled && endAt > 0 && System.currentTimeMillis() >= endAt) {
            try { stop(); return true; } catch (Exception ignored) {}
        }
        return false;
    }
    public long remainingSeconds() { return enabled && endAt>0 ? Math.max(0,(endAt-System.currentTimeMillis())/1000) : 0; }
    public int hearts(UUID uuid) { try { return db.hearts(uuid,startHearts); } catch (Exception e) { return startHearts; } }
    public void setHearts(UUID uuid, int value) {
        int safe = Math.max(0, Math.min(maxHearts, value));
        try { db.setHearts(uuid, safe); Object player=findPlayer(uuid); if(player!=null && enabled && safe>0) apply(player); }
        catch (Exception ignored) { }
    }
    public void start() throws Exception {
        enabled=true; long startedAt=System.currentTimeMillis(); eventStartedAt=startedAt; endAt=durationSeconds>0?startedAt+durationSeconds*1000L:0;
        db.setting("lifesteal.enabled","true"); db.setting("lifesteal.endAt",String.valueOf(endAt)); db.setting("lifesteal.eventStartedAt",String.valueOf(startedAt));
        for(Object p: onlinePlayers()) { UUID id=(UUID)Reflect.call(p,"getUniqueId"); db.setHearts(id,startHearts); db.addLifeStealParticipant(eventStartedAt,id); try{Reflect.call(p,"setGameMode",Reflect.enumValue("org.bukkit.GameMode","SURVIVAL"));}catch(Exception ignored){} apply(p); }
    }
    public void stop() throws Exception {
        enabled=false; endAt=0; db.setting("lifesteal.enabled","false"); db.setting("lifesteal.endAt","0");
        for(Object p: onlinePlayers()) { try{UUID id=(UUID)Reflect.call(p,"getUniqueId"); if(db.hearts(id,startHearts)<=0) Reflect.call(p,"setGameMode",Reflect.enumValue("org.bukkit.GameMode","SURVIVAL"));}catch(Exception ignored){} restore(p); }
    }
    public void applyOnJoin(Object player) {
        UUID id=(UUID)Reflect.call(player,"getUniqueId");
        if(!enabled) {
            // Offline players may have left during an event while carrying an event-only max-health or spectator state.
            restore(player);
            if(hearts(id)<=0) Reflect.scheduleLater(plugin,()->{try{Reflect.call(player,"setGameMode",Reflect.enumValue("org.bukkit.GameMode","SURVIVAL"));}catch(Exception ignored){}},2L);
            return;
        }
        try {
            if(!db.isLifeStealParticipant(eventStartedAt,id)) {
                db.setHearts(id,startHearts);
                db.addLifeStealParticipant(eventStartedAt,id);
            }
        } catch(Exception ignored) { }
        if(hearts(id)<=0) {
            Reflect.scheduleLater(plugin,()->{try{Reflect.call(player,"setGameMode",Reflect.enumValue("org.bukkit.GameMode","SPECTATOR"));}catch(Exception ignored){}},2L);
        } else apply(player);
    }
    public void onKill(Object killer,Object victim) {
        if(!enabled || killer==null || victim==null) return;
        try {
            UUID kid=(UUID)Reflect.call(killer,"getUniqueId"), vid=(UUID)Reflect.call(victim,"getUniqueId");
            if(kid.equals(vid)) return;
            db.addLifeStealParticipant(eventStartedAt,kid); db.addLifeStealParticipant(eventStartedAt,vid);
            String a=kid.toString(),b=vid.toString(); String pair=a.compareTo(b)<0?a+":"+b:b+":"+a; long now=System.currentTimeMillis();
            if(now-antiFarm.getOrDefault(pair,0L)<60_000L) return;
            antiFarm.put(pair,now);
            int victimHearts=Math.max(0,hearts(vid)-heartsLost); int killerHearts=Math.min(maxHearts,hearts(kid)+heartsPerKill);
            db.setHearts(vid,victimHearts); db.setHearts(kid,killerHearts);
            apply(killer);
            Reflect.call(killer,"sendMessage","§a+"+heartsPerKill+" tim §7(hiện có: "+killerHearts+")");
            Reflect.call(victim,"sendMessage","§c-"+heartsLost+" tim §7(hiện có: "+victimHearts+")");
        } catch(Exception ignored) {}
    }
    public void apply(Object player) {
        try {
            UUID id=(UUID)Reflect.call(player,"getUniqueId"); int h=Math.max(1,hearts(id));
            Object attribute=Reflect.fieldValue("org.bukkit.attribute.Attribute","MAX_HEALTH");
            Object instance=Reflect.call(player,"getAttribute",attribute);
            if(instance!=null) { Reflect.call(instance,"setBaseValue",Math.min(40.0,Math.max(2.0,h*2.0))); double current=((Number)Reflect.call(player,"getHealth")).doubleValue(); double max=((Number)Reflect.call(instance,"getValue")).doubleValue(); if(current>max)Reflect.call(player,"setHealth",max); }
        } catch(Exception ignored) {}
    }
    public void restore(Object player) {
        try {
            Object attribute=Reflect.fieldValue("org.bukkit.attribute.Attribute","MAX_HEALTH"); Object instance=Reflect.call(player,"getAttribute",attribute);
            if(instance!=null){Reflect.call(instance,"setBaseValue",20.0);double current=((Number)Reflect.call(player,"getHealth")).doubleValue();if(current>20)Reflect.call(player,"setHealth",20.0);}
        } catch(Exception ignored) {}
    }
    public void onRespawn(Object player) {
        if(!enabled)return;
        int h=hearts((UUID)Reflect.call(player,"getUniqueId"));
        Reflect.scheduleLater(plugin,()->{
            if(h<=0){try{Reflect.call(player,"setGameMode",Reflect.enumValue("org.bukkit.GameMode","SPECTATOR"));}catch(Exception ignored){}}
            else apply(player);
        },2L);
    }
    public boolean revive(UUID uuid,int hearts) {
        try { db.setHearts(uuid,Math.max(1,Math.min(maxHearts,hearts))); Object player=findPlayer(uuid); if(player!=null){Reflect.call(player,"setGameMode",Reflect.enumValue("org.bukkit.GameMode","SURVIVAL"));apply(player);}return true; }
        catch(Exception e){return false;}
    }
    public List<String[]> top(int limit){try{return enabled?db.eventHeartTop(eventStartedAt,limit):db.heartTop(limit);}catch(Exception e){return List.of();}}
    public List<String[]> eventTop(long eventId,int limit){try{return db.eventHeartTop(eventId,limit);}catch(Exception e){return List.of();}}
    public long eventStartedAt(){return eventStartedAt;}
    private Object findPlayer(UUID id){for(Object p:onlinePlayers())if(id.equals(Reflect.call(p,"getUniqueId")))return p;return null;}
    private Collection<?> onlinePlayers(){Object value=Reflect.callStatic("org.bukkit.Bukkit","getOnlinePlayers");return value instanceof Collection<?> c?c:List.of();}
}
