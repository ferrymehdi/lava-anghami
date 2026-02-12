package org.ferrymehdi.plugin.config;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;

import org.ferrymehdi.plugin.anghami.AnghamiAudioSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class Plugin implements AudioPlayerManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(Plugin.class);

    private final ConfigPlugin pluginConfig;

    public Plugin(ConfigPlugin pluginConfig) {
        log.info("Loading Anghami plugin...");
        this.pluginConfig = pluginConfig;
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        if(pluginConfig.isEnabled()) {
            log.info("Registering Anghami audio source manager");
            manager.registerSourceManager(
                    new AnghamiAudioSourceManager(
                            pluginConfig.getAnghamiToken(),
                            pluginConfig.getReqKey(),
                            pluginConfig.getResKey(),
                            pluginConfig.getLanguage())
            );
        }
        return manager;
    }
}
