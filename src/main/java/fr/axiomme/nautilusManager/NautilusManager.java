package fr.axiomme.nautilusManager;

import fr.axiomme.nautilusManager.web.PanelServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

public class NautilusManager extends JavaPlugin {

    private PanelServer panel;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String token = getConfig().getString("token", "");
        if (token.isBlank() || token.equals("change-me")) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            getConfig().set("token", token);
            saveConfig();
            getLogger().warning("Jeton d'accès généré (aussi dans config.yml) : " + token);
        }

        try {
            panel = new PanelServer(this);
            panel.start();
        } catch (IOException e) {
            getLogger().severe("Impossible de démarrer le serveur web : " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (panel != null) {
            panel.stop();
        }
    }
}