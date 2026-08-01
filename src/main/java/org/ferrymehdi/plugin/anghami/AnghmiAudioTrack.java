package org.ferrymehdi.plugin.anghami;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URL;

public class AnghmiAudioTrack extends DelegatedAudioTrack {
    private final AnghamiAudioSourceManager sourceManager;

    public AnghmiAudioTrack(AudioTrackInfo trackInfo, AnghamiAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String finalUrl = getDirectUrl(trackInfo.identifier);
        if (finalUrl != null && !finalUrl.isEmpty()) {
            try (var stream = new PersistentHttpStream(sourceManager.getHttpInterface(), new URL(finalUrl).toURI(), trackInfo.length)) {
                processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
            }
        }
    }

    public String getDirectUrl(String trackId) {
        return this.sourceManager.getTrackResolver().getTrackUrl(trackId);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new AnghmiAudioTrack(this.trackInfo, this.sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return this.sourceManager;
    }
}
