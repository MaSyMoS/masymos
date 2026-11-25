package org.masymos.core.extract;

import java.io.IOException;
import java.io.InputStream;

public interface ModelSourceContentLoader {
    InputStream open(ModelSource source) throws IOException;
}
