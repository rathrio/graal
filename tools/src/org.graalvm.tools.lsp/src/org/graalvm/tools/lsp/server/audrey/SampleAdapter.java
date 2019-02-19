package org.graalvm.tools.lsp.server.audrey;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class SampleAdapter extends TypeAdapter<Sample> {
    @Override
    public void write(final JsonWriter out, final Sample sample) throws IOException {
        out.beginObject();
        out.name("identifier").value(sample.getIdentifier());
        out.name("metaObject").value(sample.getMetaObject());
        out.name("value").value(sample.getValue());
        out.name("rootNodeId").value(sample.getRootNodeId());
        out.name("category").value(sample.getCategory().name());
        out.name("source").value(sample.getSource());
        out.name("sourceLine").value(sample.getSourceLine());
        out.name("sourceIndex").value(sample.getSourceIndex());
        out.name("sourceLength").value(sample.getSourceLength());
        out.name("sourceCharacters").value(sample.getSourceCharacters().toString());
        out.endObject();
    }

    @Override
    public Sample read(final JsonReader in) throws IOException {
        final Sample sample = new Sample();

        in.beginObject();
        while (in.hasNext()) {
            switch (in.nextName()) {
                case "metaObject":
                    sample.setMetaObject(in.nextString());
                    break;
                case "value":
                    sample.setValue(in.nextString());
                    break;
                case "rootNodeId":
                    sample.setRootNodeId(in.nextString());
                    break;
                case "category":
                    sample.setCategory(Sample.Category.valueOf(in.nextString().toUpperCase()));
                    break;
                case "source":
                    sample.setSource(in.nextString());
                    break;
                case "sourceLine":
                    sample.setSourceLine(in.nextInt());
                    break;
                case "sourceIndex":
                    sample.setSourceIndex(in.nextInt());
                    break;
                case "sourceLength":
                    sample.setSourceLength(in.nextInt());
                    break;
                case "sourceCharacters":
                    sample.setSourceCharacters(in.nextString());
                    break;
                case "identifier":
                    sample.setIdentifier(in.nextString());
                    break;
            }
        }
        in.endObject();

        return sample;
    }
}
