package org.bukkit.plugin.java;
import java.io.File;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.FileConfiguration;
public class JavaPlugin {
 public void onEnable(){}
 public void onDisable(){}
 public boolean onCommand(CommandSender sender, Command command, String label, String[] args){return false;}
 public File getDataFolder(){return new File("plugins/LTZCore");}
 public Logger getLogger(){return Logger.getLogger("LTZCore");}
 public void saveDefaultConfig(){}
 public void saveConfig(){}
 public void saveResource(String path, boolean replace){}
 public void reloadConfig(){}
 public FileConfiguration getConfig(){return null;}
}
