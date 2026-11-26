package vn.ltzcore.economy;

import vn.ltzcore.storage.Database;
import java.util.List;
import java.util.UUID;

public final class EconomyManager {
    private final Database db;
    public EconomyManager(Database db) { this.db = db; }
    public long balance(UUID uuid) { try { return db.balance(uuid); } catch (Exception e) { return 0; } }
    public boolean deposit(UUID uuid, long amount, String reason) {
        if (amount <= 0) return false;
        try { long current=db.balance(uuid); db.setBalance(uuid,Math.addExact(current,amount)); db.logTransaction(null,uuid,amount,reason); return true; }
        catch (Exception e) { return false; }
    }
    public boolean withdraw(UUID uuid, long amount, String reason) {
        if (amount <= 0) return false;
        try { long current=db.balance(uuid); if(current<amount)return false; db.setBalance(uuid,current-amount); db.logTransaction(uuid,null,amount,reason); return true; }
        catch (Exception e) { return false; }
    }
    public boolean pay(UUID from, UUID to, long amount) { try { return db.transfer(from,to,amount); } catch (Exception e) { return false; } }
    public List<String[]> top(int limit) { try { return db.baltop(limit); } catch (Exception e) { return List.of(); } }
}
