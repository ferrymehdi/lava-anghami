package org.ferrymehdi.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@ConfigurationProperties(prefix = "plugins.lava-anghami")
@Component
public class ConfigPlugin {
    private String anghamiToken;
    private String resKey;
    private String reqKey;
    private boolean enabled;
    private String language;

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAnghamiToken() {
        return this.anghamiToken;
    }

    public void setAnghamiToken(String anghamiToken) {
        this.anghamiToken = anghamiToken;
    }

    public String getResKey() {
        return this.resKey;
    }

    public void setResKey(String resKey) {
        this.resKey = resKey;
    }

    public String getReqKey() {
        return this.reqKey;
    }

    public void setReqKey(String reqKey) {
        this.reqKey = reqKey;
    }

    public String getLanguage() {
        return this.language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
