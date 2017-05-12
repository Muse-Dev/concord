package com.walmartlabs.concord.project.yaml.model;

import com.fasterxml.jackson.core.JsonLocation;
import io.takari.bpm.model.ScriptTask.Type;

public class YamlScript extends YamlStep {

    private final Type type;
    private final String language;
    private final String body;

    public YamlScript(JsonLocation location, Type type, String language, String body) {
        super(location);
        this.type = type;
        this.language = language;
        this.body = body;
    }

    public Type getType() {
        return type;
    }

    public String getLanguage() {
        return language;
    }

    public String getBody() {
        return body;
    }

    @Override
    public String toString() {
        return "YamlScript{" +
                "type=" + type +
                ", language='" + language + '\'' +
                ", body='" + body + '\'' +
                '}';
    }
}
