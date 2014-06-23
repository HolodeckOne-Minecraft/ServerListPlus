/*
 *        _____                     __    _     _   _____ _
 *       |   __|___ ___ _ _ ___ ___|  |  |_|___| |_|  _  | |_ _ ___
 *       |__   | -_|  _| | | -_|  _|  |__| |_ -|  _|   __| | | |_ -|
 *       |_____|___|_|  \_/|___|_| |_____|_|___|_| |__|  |_|___|___|
 *
 *  ServerListPlus - Customize your complete server status ping!
 *  Copyright (C) 2014, Minecrell <https://github.com/Minecrell>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.minecrell.serverlistplus.core;

import net.minecrell.serverlistplus.core.config.CoreConf;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.config.ServerStatusConf;
import net.minecrell.serverlistplus.core.config.help.ConfExamples;
import net.minecrell.serverlistplus.core.plugin.ServerCommandSender;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.util.Format;

import java.net.InetAddress;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;

/**
 * Represents the core part of the ServerListPlus plugin.
 */
public class ServerListPlusCore {
    private final CoreDescription info;

    private final ServerListPlusPlugin plugin;
    private final ServerListPlusLogger logger;

    private final ConfigurationManager configManager;
    private final ProfileManager profileManager;
    private final ServerStatusManager statusManager;

    private Cache<String, String> playerTracker;
    private String playerTrackerConf;

    private String faviconCacheConf;

    public ServerListPlusCore(ServerListPlusPlugin plugin) throws ServerListPlusException {
        this.plugin = Preconditions.checkNotNull(plugin, "plugin");
        this.logger = new ServerListPlusLogger(this);

        this.getLogger().info("Loading Core...");
        this.info = CoreDescription.load(this);

        this.statusManager = new ServerStatusManager(this);
        this.configManager = new ConfigurationManager(this);

        this.registerConf(ServerStatusConf.class, ConfExamples.forServerStatus(), "Status");
        this.registerConf(PluginConf.class, ConfExamples.forPlugin(), "Plugin");
        this.registerConf(CoreConf.class, ConfExamples.forCore(), "Core");

        this.profileManager = new ProfileManager(this);

        plugin.initialize(this);
        this.reload();

        this.getLogger().info("Core was successfully loaded.");
    }

    public <T> void registerConf(Class<T> clazz, T def, String alias) {
        configManager.getDefaults().set(clazz, def);
        configManager.getYAML().registerAlias(clazz, alias);
    }

    private void reloadCaches() {
        CoreConf conf = this.getConf(CoreConf.class);
        boolean enabled = this.getConf(PluginConf.class).PlayerTracking;

        if (!enabled || (playerTrackerConf == null || conf.Caches == null
                || !playerTrackerConf.equals(conf.Caches.PlayerTracking))) {

            if (playerTracker != null) {
                this.getLogger().info("Deleting old player tracking cache due to configuration changes.");
                playerTracker.invalidateAll();
                playerTracker.cleanUp();
                this.playerTracker = null;
            }

            if (enabled) {
                this.getLogger().info("Creating new player tracking cache...");

                try {
                    Preconditions.checkArgument(conf.Caches != null, "Cache configuration section not found");
                    this.playerTrackerConf = conf.Caches.PlayerTracking;
                    this.playerTracker = CacheBuilder.from(playerTrackerConf).build();
                } catch (IllegalArgumentException e) {
                    this.getLogger().severe(e, "Unable to create player tracker cache using configuration " +
                            "settings.");
                    this.playerTrackerConf = this.getDefaultConf(CoreConf.class).Caches.PlayerTracking;
                    this.playerTracker = CacheBuilder.from(playerTrackerConf).build();
                }
            } else
                playerTrackerConf = null;
        }

        enabled = statusManager.hasFavicon();

        if (!enabled || (faviconCacheConf == null || conf.Caches == null
                || !faviconCacheConf.equals(conf.Caches.Favicon))) {
            if (plugin.getFaviconCache() != null) {
                this.getLogger().info("Deleting old favicon cache due to configuration changes.");
                plugin.reloadFaviconCache(null);
            }

            if (enabled) {
                this.getLogger().info("Creating new favicon cache...");

                try {
                    Preconditions.checkArgument(conf.Caches != null, "Cache configuration section not found");
                    this.faviconCacheConf = conf.Caches.Favicon;
                    plugin.reloadFaviconCache(CacheBuilderSpec.parse(faviconCacheConf));
                } catch (IllegalArgumentException e) {
                    this.getLogger().severe(e, "Unable to create favicon cache using configuration settings.");
                    this.faviconCacheConf = this.getDefaultConf(CoreConf.class).Caches.Favicon;
                    plugin.reloadFaviconCache(CacheBuilderSpec.parse(faviconCacheConf));
                }
            } else
                faviconCacheConf = null;
        }
    }

    public void reload() throws ServerListPlusException {
        configManager.reload();
        this.profileManager.reload();
        if (!profileManager.isEnabled())
            this.getLogger().warning("ServerListPlus profile is not enabled, nothing will be changed on the " +
                    "server!");
        statusManager.reload();
        this.reloadCaches();
    }

    public void addClient(String playerName, InetAddress client) {
        if (this.playerTracker != null) playerTracker.put(client.getHostAddress(), playerName);
    }

    public String resolveClient(InetAddress client) {
        return this.playerTracker != null ? playerTracker.getIfPresent(client.getHostAddress()) : null;
    }

    public void executeCommand(ServerCommandSender sender, String cmd, String[] args) {
        String sub = (args.length > 0) ? args[0] : null;
        if (sub != null) {
            if (sub.equalsIgnoreCase("reload")) {
                this.getLogger().infoF("Reloading configuration per request by %s!", sender);
                sender.sendMessage(Format.GREEN + "Reloading configuration...");

                try {
                    this.reload();
                    sender.sendMessage(Format.GREEN + "Configuration successfully reloaded!");
                } catch (ServerListPlusException e) {
                    sender.sendMessage(Format.RED + "An internal error occurred while reloading the " +
                            "configuration.");
                }

                return;
            } else if (sub.equalsIgnoreCase("save")) {
                this.getLogger().infoF("Saving configuration per request by %s!", sender);
                sender.sendMessage(Format.GREEN + "Saving configuration...");

                try {
                    configManager.save();
                    sender.sendMessage(Format.GREEN + "Configuration successfully saved.");
                } catch (ServerListPlusException e) {
                    sender.sendMessage(Format.RED + "An internal error occurred while saving the configuration.");
                }
            } else if (sub.equalsIgnoreCase("enable") || sub.equalsIgnoreCase("disable")) {
                boolean enable = sub.equalsIgnoreCase("enable");
                String tmp = enable ? "Enabling" : "Disabling";
                this.getLogger().infoF("%s ServerListPlus per request of %s...", tmp, sender);
                sender.sendMessage(Format.GREEN + tmp + " ServerListPlus...");

                try {
                    profileManager.setEnabled(enable);
                    sender.sendMessage(Format.GREEN + "ServerListPlus has been successfully " + (enable ?
                            "enabled" : "disabled") + "!");
                } catch (ServerListPlusException e) {
                    sender.sendMessage(Format.RED + "An internal error occurred while " + (enable ? "enabling" :
                            "disabling") + " ServerListPlus.");
                }

                return;
            }
        }

        sender.sendMessage(Format.GOLD + info.getName() + plugin.getServerType() + " v" + info.getVersion());
        if (info.getDescription() != null)
            sender.sendMessage(Format.GRAY + info.getDescription());
        if (info.getAuthor() != null)
            sender.sendMessage(Format.GOLD + "Author: " + Format.GRAY + info.getAuthor());
        if (info.getWebsite() != null)
            sender.sendMessage(Format.GOLD + "Website: " + Format.GRAY + info.getWebsite());
        if (info.getWiki() != null)
            sender.sendMessage(Format.GOLD + "Wiki: " + Format.GRAY + info.getWiki());

        sender.sendMessages(
                Format.GOLD + "Commands:",
                buildCommandHelp("", "Display an information page about the plugin and list all available " +
                        "commands."),
                buildCommandHelp("reload", "Reload the plugin configuration."),
                buildCommandHelp("save", "Save the plugin configuration."),
                buildCommandHelp("enable", "Enable the plugin and start modifying the status ping."),
                buildCommandHelp("disable", "Disable the plugin and stop modifying the status ping.")
        );
    }

    private static String buildCommandHelp(String cmd, String description) {
        return Format.RED + "/serverlistplus " + cmd + Format.WHITE + " - " + Format.GRAY + description;
    }

    public ServerListPlusLogger getLogger() {
        return logger;
    }

    public ServerListPlusPlugin getPlugin() {
        return plugin;
    }

    public CoreDescription getInfo() {
        return info;
    }

    public ConfigurationManager getConf() {
        return configManager;
    }

    public <T> T getConf(Class<T> clazz) {
        return this.getConf().getStorage().get(clazz);
    }

    public <T> T getDefaultConf(Class<T> clazz) {
        return this.getConf().getDefaults().get(clazz);
    }

    public ProfileManager getProfiles() {
        return profileManager;
    }

    public ServerStatusManager getStatus() {
        return statusManager;
    }
}
